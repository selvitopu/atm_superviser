package io.github.selvitopu.atmsup.supervisor.polling;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;

/**
 * One Resilience4j circuit breaker per device, guarding its full-status polls only; probes never go through it.
 * Breaker state is kept apart from device health, and is published as Micrometer metrics
 * ({@code resilience4j.circuitbreaker.*}, tag {@code name} = equipment id).
 */
public final class DeviceBreakers {

    private final CircuitBreakerRegistry registry;

    public DeviceBreakers(MeterRegistry meters, CircuitBreakerConfig config) {
        registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
    }

    /** The breaker named after the equipment id, created on first use. */
    public CircuitBreaker forDevice(String equipmentId) {
        return registry.circuitBreaker(equipmentId);
    }

    /**
     * Opens at 50 % failures over the last 5 full polls (at least 3), stays open 30 s, and then lets exactly one full
     * poll through; the first call after the wait moves it to HALF_OPEN (no automatic transition). Only
     * {@link SnmpRequestException} counts as a failure.
     */
    public static CircuitBreakerConfig defaults() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordExceptions(SnmpRequestException.class)
                .build();
    }
}
