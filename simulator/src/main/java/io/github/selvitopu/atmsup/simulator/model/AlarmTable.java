package io.github.selvitopu.atmsup.simulator.model;

import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmCleared;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The device-side alarm table: keeps the active alarms and turns a desired alarm set into notifications. */
public final class AlarmTable {

    private record Key(int code, int instance) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            return Comparator.comparingInt(Key::code).thenComparingInt(Key::instance).compare(this, o);
        }
    }

    private final Map<Key, ActiveAlarm> active = new TreeMap<>();

    /**
     * Makes the active set equal {@code desired}. Alarms no longer desired are cleared; new ones are raised at
     * {@code now}; one whose severity or text changed is raised again with its original {@code raisedAt}. Clears come
     * first, then raises, each ordered by (code, instance).
     */
    public List<DeviceNotification> reconcile(Collection<AlarmSpec> desired, Instant now) {
        Map<Key, AlarmSpec> wanted = new TreeMap<>();
        for (AlarmSpec spec : desired) {
            wanted.put(new Key(spec.code(), spec.instance()), spec);
        }
        List<DeviceNotification> clears = new ArrayList<>();
        List<DeviceNotification> raises = new ArrayList<>();
        for (Key key : List.copyOf(active.keySet())) {
            if (!wanted.containsKey(key)) {
                active.remove(key);
                clears.add(new AlarmCleared(key.code(), key.instance()));
            }
        }
        for (Map.Entry<Key, AlarmSpec> entry : wanted.entrySet()) {
            AlarmSpec spec = entry.getValue();
            ActiveAlarm current = active.get(entry.getKey());
            if (current != null && current.severity() == spec.severity() && current.text().equals(spec.text())
                    && current.instanceName().equals(spec.instanceName())) {
                continue;
            }
            Instant raisedAt = current == null ? now : current.raisedAt();
            ActiveAlarm alarm = new ActiveAlarm(spec.code(), spec.instance(), spec.instanceName(), spec.severity(),
                    spec.text(), raisedAt);
            active.put(entry.getKey(), alarm);
            raises.add(new AlarmRaised(alarm));
        }
        clears.addAll(raises);
        return clears;
    }

    /** The active alarms, ordered by (code, instance). */
    public List<ActiveAlarm> active() {
        return List.copyOf(active.values());
    }
}
