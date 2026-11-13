package io.github.selvitopu.atmsup.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EngineIdsTest {

    @Test
    void engineIdIsTextFormatUnderEnterprise32473() {
        assertThat(EngineIds.hex(EngineIds.of("RAD-N"))).isEqualTo("80007ed9045241442d4e");
    }

    @Test
    void nameLengthIsBounded() {
        assertThatThrownBy(() -> EngineIds.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineIds.of("X".repeat(28))).isInstanceOf(IllegalArgumentException.class);
        assertThat(EngineIds.of("X".repeat(27))).hasSize(32);
    }
}
