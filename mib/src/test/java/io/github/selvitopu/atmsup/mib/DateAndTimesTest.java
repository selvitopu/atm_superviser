package io.github.selvitopu.atmsup.mib;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.OctetString;

class DateAndTimesTest {

    @Test
    void roundTripsMillisecondsTruncatedToDeciseconds() {
        Instant t = Instant.parse("2026-11-13T14:02:37.450Z");
        assertThat(DateAndTimes.decode(DateAndTimes.encode(t))).isEqualTo(Instant.parse("2026-11-13T14:02:37.400Z"));
    }

    @Test
    void encodesElevenBytesWithUtcOffset() {
        OctetString s = DateAndTimes.encode(Instant.parse("2026-11-13T14:02:37.450Z"));
        assertThat(s.toByteArray()).containsExactly(0x07, 0xEA, 11, 13, 14, 2, 37, 4, '+', 0, 0);
    }

    @Test
    void decodesEightByteFormAsUtc() {
        OctetString s = new OctetString(new byte[] {0x07, (byte) 0xEA, 11, 13, 14, 2, 37, 4});
        assertThat(DateAndTimes.decode(s)).isEqualTo(Instant.parse("2026-11-13T14:02:37.400Z"));
    }

    @Test
    void decodesNonUtcOffset() {
        OctetString s = new OctetString(new byte[] {0x07, (byte) 0xEA, 11, 13, 16, 2, 37, 0, '+', 2, 0});
        assertThat(DateAndTimes.decode(s)).isEqualTo(Instant.parse("2026-11-13T14:02:37Z"));
    }

    @Test
    void rejectsOtherLengths() {
        assertThatThrownBy(() -> DateAndTimes.decode(new OctetString(new byte[9])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DateAndTimes.decode(new OctetString(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
