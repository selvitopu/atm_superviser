package io.github.selvitopu.atmsup.supervisor.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AlarmFloodPolicyTest {

    @Test
    void anInvalidFloodPolicyIsRejected() {
        Duration ten = Duration.ofMinutes(10);
        assertThatIllegalArgumentException().isThrownBy(() -> new AlarmFloodPolicy(ten, 5, 6));
        assertThatIllegalArgumentException().isThrownBy(() -> new AlarmFloodPolicy(ten, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new AlarmFloodPolicy(ten, 5, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new AlarmFloodPolicy(Duration.ZERO, 5, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new AlarmFloodPolicy(ten.negated(), 5, 2));
    }

    @Test
    void theDefaultIsTenInTenMinutesEndingBelowFive() {
        assertThat(AlarmFloodPolicy.DEFAULT).isEqualTo(new AlarmFloodPolicy(Duration.ofMinutes(10), 10, 5));
    }
}
