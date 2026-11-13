package io.github.selvitopu.atmsup.inventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists {@code snmpEngineBoots} per engine name in a flat JSON file such as {@code {"RAD-N":2}}.
 * Writes are atomic (temp file then atomic move).
 */
public class EngineBootsStore {

    private static final Pattern ENTRY = Pattern.compile("\"([^\"\\\\]+)\"\\s*:\\s*(\\d{1,9})");

    private final Path file;

    public EngineBootsStore(Path file) {
        this.file = file;
    }

    public synchronized int nextBoots(String engineName) {
        Map<String, Integer> boots = read();
        int next = boots.getOrDefault(engineName, 0) + 1;
        boots.put(engineName, next);
        write(boots);
        return next;
    }

    private Map<String, Integer> read() {
        Map<String, Integer> boots = new TreeMap<>();
        if (!Files.exists(file)) {
            return boots;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (!json.startsWith("{") || !json.endsWith("}")) {
                throw new IllegalStateException("Corrupt engine boots file " + file);
            }
            String body = json.substring(1, json.length() - 1).strip();
            Matcher m = ENTRY.matcher(body);
            int pos = 0;
            while (m.find()) {
                String gap = body.substring(pos, m.start()).strip();
                if (!(pos == 0 ? gap.isEmpty() : gap.equals(","))) {
                    throw new IllegalStateException("Corrupt engine boots file " + file);
                }
                boots.put(m.group(1), Integer.parseInt(m.group(2)));
                pos = m.end();
            }
            if (!body.substring(pos).isBlank()) {
                throw new IllegalStateException("Corrupt engine boots file " + file);
            }
            return boots;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read engine boots file " + file, e);
        }
    }

    private void write(Map<String, Integer> boots) {
        StringBuilder json = new StringBuilder("{");
        boots.forEach((name, n) -> {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(name).append("\":").append(n);
        });
        json.append('}');
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write engine boots file " + file, e);
        }
    }
}
