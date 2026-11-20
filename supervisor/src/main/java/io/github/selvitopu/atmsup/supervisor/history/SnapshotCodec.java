package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.board.AlarmFlood;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.history.EventCodec.StoredServiceState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps the durable board to the versioned JSON of a snapshot row and back. The board is stored as lists (its maps
 * are keyed by records); the last event id has its own column, so it is not in the JSON.
 */
@Component
public class SnapshotCodec {

    static final int SCHEMA_VERSION = 1;

    private final JsonMapper mapper;

    public SnapshotCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Board b) {
        return mapper.writeValueAsString(new Payload(SCHEMA_VERSION, List.copyOf(b.equipment().values()),
                b.services().values().stream().map(StoredServiceState::of).toList(),
                List.copyOf(b.openAlarms().values()), b.flood()));
    }

    /** @throws IllegalStateException if the JSON has an unsupported schema version */
    public Board decode(String json, long lastEventId) {
        Payload p = mapper.readValue(json, Payload.class);
        if (p.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalStateException("Board snapshot has schema version " + p.schemaVersion()
                    + "; supported is " + SCHEMA_VERSION);
        }
        Map<String, EquipmentState> equipment = new LinkedHashMap<>();
        p.equipment().forEach(e -> equipment.put(e.id(), e));
        var services = new LinkedHashMap<ServiceId, ServiceState>();
        p.services().forEach(s -> services.put(s.id(), s.toState()));
        var alarms = new LinkedHashMap<AlarmKey, Alarm>();
        p.openAlarms().forEach(a -> alarms.put(a.key(), a));
        return new Board(equipment, services, alarms, lastEventId, p.flood() == null ? AlarmFlood.NONE : p.flood());
    }

    private record Payload(int schemaVersion, List<EquipmentState> equipment, List<StoredServiceState> services,
                           List<Alarm> openAlarms, AlarmFlood flood) {
    }
}
