package io.github.selvitopu.atmsup.mib;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SnmpEnumTest {

    @Test
    void fromCodeRoundTrips() {
        assertThat(SnmpEnum.fromCode(Role.class, 3)).isEqualTo(Role.STANDBY);
    }

    @Test
    void unknownCodeFails() {
        assertThatThrownBy(() -> SnmpEnum.fromCode(Role.class, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown Role code 9");
    }

    @Test
    void severityOrder() {
        assertThat(Severity.CRITICAL.moreSevereThan(Severity.MAJOR)).isTrue();
        assertThat(Severity.WARNING.moreSevereThan(Severity.MINOR)).isFalse();
        assertThat(Severity.INDETERMINATE.moreSevereThan(Severity.CLEARED)).isTrue();
        assertThat(Severity.WARNING.moreSevereThan(Severity.INDETERMINATE)).isTrue();
        assertThat(Severity.MAJOR.moreSevereThan(Severity.MAJOR)).isFalse();
    }

    @Test
    void codesMatchTheMib() {
        assertThat(EquipmentStatus.DEGRADED.code()).isEqualTo(2);
        assertThat(EquipmentType.RECORDER.code()).isEqualTo(7);
        assertThat(Command.RESET_COUNTERS.code()).isEqualTo(4);
        assertThat(CommandResult.REJECTED.code()).isEqualTo(4);
        assertThat(Severity.WARNING.code()).isEqualTo(6);
        assertThat(SensorInputStatus.EXCLUDED.code()).isEqualTo(3);
        assertThat(ActiveChannel.CHANNEL_B.code()).isEqualTo(2);
        assertThat(LinkStatus.DOWN.code()).isEqualTo(2);
        assertThat(ActiveTx.STANDBY.code()).isEqualTo(2);
    }
}
