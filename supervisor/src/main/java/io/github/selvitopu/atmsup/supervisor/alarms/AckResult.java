package io.github.selvitopu.atmsup.supervisor.alarms;

import java.util.List;

public record AckResult(List<AlarmChange> changes, List<Alarm> acknowledged, List<String> notFound) {
}
