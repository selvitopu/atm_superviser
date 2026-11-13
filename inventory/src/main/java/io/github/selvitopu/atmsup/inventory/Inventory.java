package io.github.selvitopu.atmsup.inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** The validated, immutable device inventory shared by simulator and supervisor. */
public final class Inventory {

    private static final Pattern ID = Pattern.compile("^[A-Z0-9-]{2,16}$");

    private final String centre;
    private final TrapTarget trapTarget;
    private final List<Device> devices;
    private final Map<String, Device> byId;

    private Inventory(String centre, TrapTarget trapTarget, List<Device> devices) {
        this.centre = centre;
        this.trapTarget = trapTarget;
        this.devices = List.copyOf(devices);
        Map<String, Device> map = new LinkedHashMap<>();
        devices.forEach(d -> map.put(d.id(), d));
        this.byId = map;
    }

    /** Validates the bound properties and resolves defaults; the exception message lists every problem. */
    public static Inventory from(InventoryProperties p) {
        List<String> problems = new ArrayList<>();
        String defaultHost = p.defaultHost() == null || p.defaultHost().isBlank() ? "localhost" : p.defaultHost();
        Duration defaultPoll = p.defaultPollInterval() == null ? Duration.ofSeconds(5) : p.defaultPollInterval();
        List<DeviceProperties> raw = p.devices() == null ? List.of() : p.devices();

        if (p.trapTarget() == null || p.trapTarget().host() == null || p.trapTarget().host().isBlank()) {
            problems.add("trap-target host is required");
        }
        if (raw.isEmpty()) {
            problems.add("No devices configured");
        }

        List<Device> devices = new ArrayList<>();
        Map<String, DeviceProperties> byId = new LinkedHashMap<>();
        Set<String> endpoints = new HashSet<>();
        for (DeviceProperties d : raw) {
            String host = d.host() == null || d.host().isBlank() ? defaultHost : d.host();
            if (d.id() == null || !ID.matcher(d.id()).matches()) {
                problems.add("Invalid id " + d.id() + " (expected " + ID.pattern() + ")");
            }
            if (byId.putIfAbsent(d.id(), d) != null) {
                problems.add("Duplicate id " + d.id());
            }
            if (d.port() < 1024 || d.port() > 65535) {
                problems.add(d.id() + ": port " + d.port() + " out of range 1024-65535");
            }
            if (!endpoints.add(host + ":" + d.port())) {
                problems.add("Duplicate port " + host + ":" + d.port());
            }
            if (d.name() == null || d.name().isBlank()) {
                problems.add(d.id() + ": name must not be blank");
            }
            if (d.type() == null) {
                problems.add(d.id() + ": type is required");
            }
            if (d.group() == null) {
                problems.add(d.id() + ": group is required");
            }
            devices.add(new Device(d.id(), d.type(), d.name(), d.group(), host, d.port(), d.pair(),
                    d.pollInterval() == null ? defaultPoll : d.pollInterval()));
        }

        for (DeviceProperties d : raw) {
            boolean paired = d.type() == DeviceType.SDPS || d.type() == DeviceType.FDPS;
            if (d.pair() == null) {
                if (paired) {
                    problems.add(d.id() + ": SDPS/FDPS needs a pair");
                }
                continue;
            }
            if (!paired) {
                problems.add(d.id() + ": only SDPS/FDPS can have a pair");
            }
            DeviceProperties partner = byId.get(d.pair());
            if (partner == null) {
                problems.add(d.id() + ": pair " + d.pair() + " does not exist");
                continue;
            }
            if (partner.type() != d.type()) {
                problems.add(d.id() + ": pair " + d.pair() + " has a different type");
            }
            if (!d.id().equals(partner.pair())) {
                problems.add(d.id() + ": pair " + d.pair() + " does not point back");
            }
        }

        if (!problems.isEmpty()) {
            throw new InventoryException("Invalid inventory:\n" + String.join("\n", problems));
        }
        return new Inventory(p.centre(), p.trapTarget(), devices);
    }

    public String centre() {
        return centre;
    }

    public List<Device> devices() {
        return devices;
    }

    public Device device(String id) {
        Device d = byId.get(id);
        if (d == null) {
            throw new NoSuchElementException("Unknown equipment " + id);
        }
        return d;
    }

    public Optional<Device> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Device> byType(DeviceType type) {
        return devices.stream().filter(d -> d.type() == type).toList();
    }

    public Optional<Device> partner(String id) {
        return Optional.ofNullable(device(id).pair()).map(byId::get);
    }

    /** True for the member of a pair that is listed first (it starts as the active unit). */
    public boolean startsActive(String id) {
        Device d = device(id);
        return partner(id).map(p -> devices.indexOf(d) < devices.indexOf(p)).orElse(true);
    }

    public TrapTarget trapTarget() {
        return trapTarget;
    }
}
