package io.github.selvitopu.atmsup.mib;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.snmp4j.smi.OctetString;

/** Conversion between {@link Instant} and the SNMPv2-TC DateAndTime octet string (RFC 2579). */
public final class DateAndTimes {

    private DateAndTimes() {}

    /** Encodes as the 11-byte form with a {@code +00:00} offset. Precision is deciseconds. */
    public static OctetString encode(Instant instant) {
        LocalDateTime t = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        int year = t.getYear();
        return new OctetString(new byte[] {
            (byte) (year >> 8),
            (byte) year,
            (byte) t.getMonthValue(),
            (byte) t.getDayOfMonth(),
            (byte) t.getHour(),
            (byte) t.getMinute(),
            (byte) t.getSecond(),
            (byte) (t.getNano() / 100_000_000),
            '+',
            0,
            0
        });
    }

    /**
     * Decodes the 8-byte form (taken as UTC) or the 11-byte form with its UTC offset.
     *
     * @throws IllegalArgumentException if the value is neither 8 nor 11 bytes long
     */
    public static Instant decode(OctetString value) {
        byte[] b = value.toByteArray();
        if (b.length != 8 && b.length != 11) {
            throw new IllegalArgumentException("DateAndTime must be 8 or 11 bytes, got " + b.length);
        }
        int year = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        LocalDateTime t = LocalDateTime.of(year, b[2] & 0xFF, b[3] & 0xFF, b[4] & 0xFF, b[5] & 0xFF, b[6] & 0xFF,
                (b[7] & 0xFF) * 100_000_000);
        ZoneOffset offset = ZoneOffset.UTC;
        if (b.length == 11) {
            int sign = b[8] == '-' ? -1 : 1;
            offset = ZoneOffset.ofHoursMinutes(sign * (b[9] & 0xFF), sign * (b[10] & 0xFF));
        }
        return t.toInstant(offset);
    }
}
