package io.github.selvitopu.atmsup.simulator.agent;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.EngineBootsStore;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.simulator.api.DeviceStateDto;
import io.github.selvitopu.atmsup.simulator.centre.Centre;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.DropTraps;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.Notify;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.RestartCompleted;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.RestartStarted;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.Responding;
import io.github.selvitopu.atmsup.simulator.centre.CentreListener;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one {@link DeviceAgent} and {@link NotificationSender} per device, routes the {@link Centre}'s events to them
 * and ticks the centre once a second. Plain Java, so tests can use it without Spring. Nothing that goes wrong in a
 * scheduler or listener thread is allowed to kill it: such failures are logged as warnings.
 */
public final class AgentHost implements AutoCloseable, CentreListener {

    private static final Logger log = LoggerFactory.getLogger(AgentHost.class);
    private static final long RESTART_CLOSE_DELAY_MS = 200;

    private final Inventory inventory;
    private final SnmpProperties creds;
    private final Centre centre;
    private final EngineBootsStore boots;
    private final String bindHost;
    private final InstantSource clock;
    private final Map<String, Unit> units = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private boolean started;
    private boolean closed;

    /** An agent with the sender that goes with it: a sender cannot outlive its agent's engine. */
    private record Unit(DeviceAgent agent, NotificationSender sender) {
    }

    public AgentHost(Inventory inv, SnmpProperties creds, Centre centre, EngineBootsStore boots, String bindHost,
                     InstantSource clock) {
        this.inventory = inv;
        this.creds = creds;
        this.centre = centre;
        this.boots = boots;
        this.bindHost = bindHost;
        this.clock = clock;
    }

    /**
     * Starts every agent, subscribes to the centre and schedules its tick.
     *
     * @throws RuntimeException if an agent cannot start (for example its port is taken); then none keeps running
     */
    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("AgentHost cannot be started again");
        }
        try {
            for (Device device : inventory.devices()) {
                units.put(device.id(), startUnit(device));
            }
        } catch (RuntimeException e) {
            closeUnits();
            throw e;
        }
        started = true;
        centre.addListener(this);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "simulator-tick");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        log.info("Started {} simulated devices", units.size());
    }

    public DeviceAgent agent(String id) {
        return unit(id).agent();
    }

    public NotificationSender sender(String id) {
        return unit(id).sender();
    }

    @Override
    public void on(List<CentreEvent> events) {
        for (CentreEvent event : events) {
            try {
                route(event);
            } catch (RuntimeException e) {
                log.warn("Cannot handle {}", event, e);
            }
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService toStop;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toStop = scheduler;
        }
        centre.removeListener(this);
        if (toStop != null) {
            toStop.shutdownNow();
        }
        closeUnits();
    }

    private void route(CentreEvent event) {
        switch (event) {
            case Notify e -> unit(e.deviceId()).sender().send(e.n());
            case Responding e -> unit(e.deviceId()).agent().setResponding(e.on());
            case DropTraps e -> unit(e.deviceId()).sender().setDropping(e.on());
            case RestartStarted e -> scheduleClose(e.deviceId());
            case RestartCompleted e -> restart(e.deviceId());
        }
    }

    private void scheduleClose(String id) {
        DeviceAgent agent = unit(id).agent();
        ScheduledExecutorService s;
        synchronized (this) {
            s = scheduler;
        }
        if (s == null) {
            throw new IllegalStateException("AgentHost is not running");
        }
        try {
            // the SET reply that caused the restart must leave before the engine goes away
            s.schedule(() -> closeQuietly(id, agent), RESTART_CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("AgentHost is closing; agent {} is closed with it", id);
        }
    }

    private void restart(String id) {
        Device device = inventory.device(id);
        Unit old = unit(id);
        closeQuietly(id, old.agent());
        synchronized (this) {
            if (closed) {
                return;
            }
            // a failed start leaves the old, closed unit in place; the device stays silent until the next restart
            Unit fresh = startUnit(device);
            units.put(id, fresh);
        }
    }

    /** A new agent and a new sender, with the fault flags the centre currently holds for the device. */
    private Unit startUnit(Device device) {
        String id = device.id();
        DeviceAgent agent = new DeviceAgent(device, creds, boots.nextBoots(id), bindHost, device.port(),
                () -> centre.view(id), centre.setHandler(id));
        agent.start();
        NotificationSender sender = new NotificationSender(agent, device, inventory.trapTarget(), clock);
        DeviceStateDto state = centre.device(id);
        agent.setResponding(state.responding());
        sender.setDropping(state.dropTraps());
        return new Unit(agent, sender);
    }

    private Unit unit(String id) {
        Unit unit = units.get(id);
        if (unit == null) {
            throw new NoSuchElementException("Unknown equipment " + id);
        }
        return unit;
    }

    private void tick() {
        try {
            centre.tick();
        } catch (RuntimeException e) {
            log.warn("Centre tick failed", e);
        }
    }

    private void closeUnits() {
        units.forEach((id, unit) -> closeQuietly(id, unit.agent()));
    }

    private void closeQuietly(String id, DeviceAgent agent) {
        try {
            agent.close();
        } catch (RuntimeException e) {
            log.warn("Cannot stop agent {}", id, e);
        }
    }
}
