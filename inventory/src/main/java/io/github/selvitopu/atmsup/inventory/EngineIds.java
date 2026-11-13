package io.github.selvitopu.atmsup.inventory;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** SNMPv3 engine IDs: text format (RFC 3411) under enterprise number 32473 (RFC 5612). */
public final class EngineIds {

    public static final String SUPERVISOR = "SUPERVISOR";

    private static final byte[] PREFIX = {(byte) 0x80, 0x00, 0x7E, (byte) 0xD9, 0x04};

    private EngineIds() {
    }

    public static byte[] of(String name) {
        if (name == null || name.isEmpty() || name.length() > 27) {
            throw new IllegalArgumentException("Engine name must be 1 to 27 characters: " + name);
        }
        byte[] text = name.getBytes(StandardCharsets.US_ASCII);
        byte[] id = new byte[PREFIX.length + text.length];
        System.arraycopy(PREFIX, 0, id, 0, PREFIX.length);
        System.arraycopy(text, 0, id, PREFIX.length, text.length);
        return id;
    }

    public static String hex(byte[] engineId) {
        return HexFormat.of().formatHex(engineId);
    }
}
