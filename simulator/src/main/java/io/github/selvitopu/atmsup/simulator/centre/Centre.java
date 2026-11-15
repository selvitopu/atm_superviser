package io.github.selvitopu.atmsup.simulator.centre;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.AtmTsOids;
import io.github.selvitopu.atmsup.mib.CommandResult;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.simulator.agent.MibView;
import io.github.selvitopu.atmsup.simulator.agent.SetCheck;
import io.github.selvitopu.atmsup.simulator.agent.SetHandler;
import io.github.selvitopu.atmsup.simulator.api.DeviceStateDto;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.DropTraps;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.Notify;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.Responding;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.RestartCompleted;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.RestartStarted;
import io.github.selvitopu.atmsup.simulator.model.DeviceModel;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.Switchover;
import io.github.selvitopu.atmsup.simulator.model.Emitted;
import io.github.selvitopu.atmsup.simulator.model.Fault;
import io.github.selvitopu.atmsup.simulator.model.FaultRequest;
import io.github.selvitopu.atmsup.simulator.model.FdpsModel;
import io.github.selvitopu.atmsup.simulator.model.RadarModel;
import io.github.selvitopu.atmsup.simulator.model.RedundantPair;
import io.github.selvitopu.atmsup.simulator.model.SdpsModel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;

/**
 * The simulated centre: every device model and redundant pair, behind one lock. Mutators change the models, recompute
 * the SDPS sensor feeds from the radars, rebuild every {@link MibView} (agents only read the volatile view map), and
 * only after releasing the lock hand the resulting events to the listeners, in order: {@link Responding} and
 * {@link DropTraps} flags first, then notifications in emission order; {@link RestartCompleted} events come before
 * both, so a fresh agent exists when the flags and notifications of the same batch reach it. Batches reach the listeners in the order they were produced, one at a time; a listener
 * may read the centre but must not mutate it.
 */
public final class Centre {

    private static final Logger log = LoggerFactory.getLogger(Centre.class);

    private static final int CMD_BECOME_ACTIVE = 2;
    private static final int CMD_RESTART = 3;
    private static final int CMD_RESET_COUNTERS = 4;

    private final InstantSource clock;
    private final Duration restartDuration;
    private final RandomGenerator rnd;
    private final Map<String, DeviceModel> models = new LinkedHashMap<>();
    private final List<SdpsModel> sdpsModels = new ArrayList<>();
    private final List<String> radarIds = new ArrayList<>();
    private final List<PairUnits> pairs = new ArrayList<>();
    private final List<CentreListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    /** Batches are numbered under {@link #lock} and delivered in that order, without holding it. */
    private final Object deliveryTurn = new Object();
    /** The batch of the {@code mutate} that holds the lock; read and written only under the lock. */
    private Batch outer;
    private long nextTicket;
    private long nowServing;
    private volatile Map<String, MibView> views = Map.of();

    /** A centre whose restarts last 8 seconds. */
    public Centre(Inventory inv, InstantSource clock, RandomGenerator rnd) {
        this(inv, clock, rnd, Duration.ofSeconds(8));
    }

    /**
     * @param restartDuration how long a device stays down after a {@code restart} command
     * @throws IllegalStateException if the inventory has a device type this version does not simulate
     */
    public Centre(Inventory inv, InstantSource clock, RandomGenerator rnd, Duration restartDuration) {
        this.clock = clock;
        this.restartDuration = restartDuration;
        this.rnd = rnd;
        Instant now = clock.instant();
        for (Device d : inv.devices()) {
            if (d.type() == DeviceType.RADAR) {
                radarIds.add(d.id());
            } else if (d.type() != DeviceType.SDPS && d.type() != DeviceType.FDPS) {
                throw new IllegalStateException("Device type " + d.type() + " is not supported in this version");
            }
        }
        for (Device d : inv.devices()) {
            Role role = d.pair() == null ? Role.STANDALONE : inv.startsActive(d.id()) ? Role.ACTIVE : Role.STANDBY;
            DeviceModel model = switch (d.type()) {
                case RADAR -> new RadarModel(d, now);
                case SDPS -> new SdpsModel(d, role, now, radarIds);
                default -> new FdpsModel(d, role, now);
            };
            models.put(d.id(), model);
            if (model instanceof SdpsModel sdps) {
                sdpsModels.add(sdps);
            }
        }
        for (Device d : inv.devices()) {
            if (d.pair() != null && inv.startsActive(d.id())) {
                DeviceModel first = models.get(d.id());
                pairs.add(new PairUnits(first, new RedundantPair(first, models.get(d.pair()))));
            }
        }
        lock.lock();
        try {
            syncFeeds(new Batch(), now);
            rebuildViews(now);
        } finally {
            lock.unlock();
        }
    }

    public void addListener(CentreListener l) {
        listeners.add(l);
    }

    public void removeListener(CentreListener l) {
        listeners.remove(l);
    }

    public List<DeviceStateDto> devices() {
        lock.lock();
        try {
            return models.values().stream().map(DeviceStateDto::of).toList();
        } finally {
            lock.unlock();
        }
    }

    /** @throws NoSuchElementException for an unknown id */
    public DeviceStateDto device(String id) {
        lock.lock();
        try {
            return DeviceStateDto.of(model(id));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a fault and returns the device's new state.
     *
     * @throws NoSuchElementException for an unknown id
     * @throws IllegalArgumentException if the fault does not apply to the device or lacks a target
     */
    public DeviceStateDto applyFault(String id, FaultRequest r) {
        return mutate(batch -> {
            DeviceModel m = model(id);
            Instant now = clock.instant();
            boolean wasUnreachable = m.unreachable();
            boolean wasDropping = m.dropTraps();
            List<DeviceNotification> notifications = m.applyFault(r, now);
            batch.flagChanges(m, wasUnreachable, wasDropping);
            batch.notifies(id, notifications);
            syncFeeds(batch, now);
            return DeviceStateDto.of(m);
        });
    }

    /** Recovers every device, switches maintenance off and makes the first unit of each pair active again. */
    public void reset() {
        mutate(batch -> {
            Instant now = clock.instant();
            FaultRequest recover = new FaultRequest(Fault.RECOVER, null);
            for (DeviceModel m : models.values()) {
                boolean wasUnreachable = m.unreachable();
                boolean wasDropping = m.dropTraps();
                batch.notifies(m.id(), m.applyFault(recover, now));
                batch.flagChanges(m, wasUnreachable, wasDropping);
                batch.notifies(m.id(), m.setMaintenance(false, now));
            }
            for (PairUnits units : pairs) {
                restoreFirstActive(units, batch, now);
            }
            syncFeeds(batch, now);
            return null;
        });
    }

    /** Advances every device, then the pairs (which read the status change times the devices just set). */
    public void tick() {
        mutate(batch -> {
            Instant now = clock.instant();
            for (DeviceModel m : models.values()) {
                if (m.restartDue(now)) {
                    completeRestart(m, batch, now);
                }
            }
            for (DeviceModel m : models.values()) {
                batch.notifies(m.id(), m.tick(now, rnd));
            }
            for (PairUnits units : pairs) {
                for (Emitted e : units.pair().tick(now)) {
                    batch.add(e.deviceId(), e.notification());
                }
            }
            syncFeeds(batch, now);
            return null;
        });
    }

    /** @throws NoSuchElementException for an unknown id */
    public MibView view(String id) {
        MibView view = views.get(id);
        if (view == null) {
            throw new NoSuchElementException("Unknown equipment " + id);
        }
        return view;
    }

    /**
     * The writable objects of a device. {@code check} and {@code apply} run under the centre lock. A failed command
     * check sets the device's last command result to REJECTED, so the reason can be read back.
     */
    public SetHandler setHandler(String id) {
        model(id);
        return new SetHandler() {
            @Override
            public <T> T atomically(Supplier<T> body) {
                return mutate(batch -> body.get());
            }

            @Override
            public SetCheck check(OID oid, Variable value) {
                return mutate(batch -> checkSet(model(id), oid, value));
            }

            @Override
            public void apply(OID oid, Variable value) {
                mutate(batch -> {
                    applySet(model(id), oid, value, batch);
                    return null;
                });
            }
        };
    }

    // ---- internals ----

    private DeviceModel model(String id) {
        DeviceModel m = models.get(id);
        if (m == null) {
            throw new NoSuchElementException("Unknown equipment " + id);
        }
        return m;
    }

    private SetCheck checkSet(DeviceModel m, OID oid, Variable value) {
        if (!oid.equals(AtmTsOids.scalar(AtmTsOids.EQ_COMMAND))) {
            return SetCheck.OK; // maintenance mode accepts both truth values
        }
        SetCheck check = switch (command(value)) {
            case CMD_BECOME_ACTIVE -> pairOf(m.id())
                    .map(units -> units.pair().checkBecomeActive(m.id()))
                    .orElse(new SetCheck(PDU.inconsistentValue, "not redundant"));
            case CMD_RESTART -> {
                if (!m.maintenance()) {
                    yield new SetCheck(PDU.inconsistentValue, "restart requires maintenance mode");
                }
                yield m.restartInProgress(clock.instant())
                        ? new SetCheck(PDU.inconsistentValue, "restart already in progress")
                        : SetCheck.OK;
            }
            default -> SetCheck.OK;
        };
        if (check.errorStatus() != PDU.noError) {
            m.setCommandResult(CommandResult.REJECTED);
        }
        return check;
    }

    private void applySet(DeviceModel m, OID oid, Variable value, Batch batch) {
        Instant now = clock.instant();
        if (!oid.equals(AtmTsOids.scalar(AtmTsOids.EQ_COMMAND))) {
            batch.notifies(m.id(), m.setMaintenance(value.toInt() == 1, now));
            syncFeeds(batch, now);
            return;
        }
        switch (command(value)) {
            case CMD_BECOME_ACTIVE -> {
                PairUnits units = pairOf(m.id()).orElseThrow(() -> new IllegalStateException("not redundant"));
                units.pair().becomeActive(m.id(), now).forEach(e -> batch.add(e.deviceId(), e.notification()));
                m.setCommandResult(CommandResult.SUCCESS);
            }
            case CMD_RESTART -> {
                m.startRestart(now.plus(restartDuration));
                batch.flags.add(new RestartStarted(m.id()));
            }
            case CMD_RESET_COUNTERS -> m.setCommandResult(CommandResult.SUCCESS); // v0.1 has no counters: a no-op
            default -> throw new IllegalArgumentException("Unsupported command " + value);
        }
        syncFeeds(batch, now);
    }

    private static int command(Variable value) {
        return value instanceof Integer32 i ? i.getValue() : -1;
    }

    private Optional<PairUnits> pairOf(String id) {
        return pairs.stream().filter(u -> u.pair().partnerOf(id).isPresent()).findFirst();
    }

    /** Role after a restart: standby if the partner is active, otherwise active; a standalone device stays so. */
    private void completeRestart(DeviceModel m, Batch batch, Instant now) {
        Role role = pairOf(m.id())
                .flatMap(units -> units.pair().partnerOf(m.id()))
                .map(partner -> partner.role() == Role.ACTIVE ? Role.STANDBY : Role.ACTIVE)
                .orElse(Role.STANDALONE);
        boolean wasUnreachable = m.unreachable();
        boolean wasDropping = m.dropTraps();
        batch.restarts.add(new RestartCompleted(m.id()));
        batch.notifies(m.id(), m.completeRestart(role, now));
        batch.flagChanges(m, wasUnreachable, wasDropping);
    }

    private void restoreFirstActive(PairUnits units, Batch batch, Instant now) {
        DeviceModel first = units.first();
        if (first.role() == Role.ACTIVE) {
            return;
        }
        DeviceModel demoted = units.pair().active();
        batch.add(demoted.id(), new Switchover(Role.STANDBY));
        batch.add(first.id(), new Switchover(Role.ACTIVE));
        batch.notifies(demoted.id(), demoted.setRole(Role.STANDBY, now));
        batch.notifies(first.id(), first.setRole(Role.ACTIVE, now));
    }

    /** An UNREACHABLE radar still delivers data; only a FAILED radar loses its feed. */
    private void syncFeeds(Batch batch, Instant now) {
        Map<String, Boolean> feeds = new LinkedHashMap<>();
        for (String radarId : radarIds) {
            feeds.put(radarId, models.get(radarId).status() != EquipmentStatus.FAILED);
        }
        for (SdpsModel sdps : sdpsModels) {
            batch.notifies(sdps.id(), sdps.updateFeeds(feeds, now));
        }
    }

    private void rebuildViews(Instant now) {
        Map<String, MibView> fresh = new LinkedHashMap<>();
        models.forEach((id, m) -> fresh.put(id, m.view(now)));
        views = Collections.unmodifiableMap(fresh);
    }

    private <T> T mutate(Function<Batch, T> body) {
        if (lock.isHeldByCurrentThread() && outer != null) {
            // Nested in SetHandler.atomically: join the outer batch. The outer mutation publishes the views and
            // delivers the events once, when its hold is released; checks read the models, not the views.
            return body.apply(outer);
        }
        Batch batch = new Batch();
        T result;
        List<CentreEvent> events;
        long ticket = -1;
        lock.lock();
        try {
            outer = batch;
            result = body.apply(batch);
            events = batch.events();
            if (!events.isEmpty()) {
                ticket = nextTicket++;
            }
        } finally {
            outer = null;
            try {
                // the one publication of this mutation, also after a body that threw: views never lag the models
                rebuildViews(clock.instant());
            } finally {
                lock.unlock();
            }
        }
        if (ticket >= 0) {
            deliver(ticket, events);
        }
        return result;
    }

    /** Waits for this batch's turn (holding no lock), runs the listeners, then passes the turn on. */
    private void deliver(long ticket, List<CentreEvent> events) {
        boolean interrupted = false;
        synchronized (deliveryTurn) {
            while (nowServing != ticket) {
                try {
                    deliveryTurn.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        try {
            for (CentreListener l : listeners) {
                try {
                    l.on(events);
                } catch (RuntimeException e) {
                    log.warn("A centre listener failed on {}", events, e);
                }
            }
        } finally {
            synchronized (deliveryTurn) {
                nowServing++;
                deliveryTurn.notifyAll();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A pair together with the unit that starts, and is restored as, the active one. */
    private record PairUnits(DeviceModel first, RedundantPair pair) {
    }

    /** Events of one mutation, grouped by the delivery order. */
    private static final class Batch {
        private final List<CentreEvent> restarts = new ArrayList<>();
        private final List<CentreEvent> flags = new ArrayList<>();
        private final List<CentreEvent> notifications = new ArrayList<>();

        void add(String deviceId, DeviceNotification n) {
            notifications.add(new Notify(deviceId, n));
        }

        void notifies(String deviceId, List<DeviceNotification> list) {
            list.forEach(n -> add(deviceId, n));
        }

        void flagChanges(DeviceModel m, boolean wasUnreachable, boolean wasDropping) {
            if (m.unreachable() != wasUnreachable) {
                flags.add(new Responding(m.id(), !m.unreachable()));
            }
            if (m.dropTraps() != wasDropping) {
                flags.add(new DropTraps(m.id(), m.dropTraps()));
            }
        }

        List<CentreEvent> events() {
            List<CentreEvent> all = new ArrayList<>(restarts);
            all.addAll(flags);
            all.addAll(notifications);
            return all;
        }
    }
}
