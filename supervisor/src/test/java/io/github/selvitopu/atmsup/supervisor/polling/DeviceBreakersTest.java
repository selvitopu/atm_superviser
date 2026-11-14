package io.github.selvitopu.atmsup.supervisor.polling;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class DeviceBreakersTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeviceBreakers breakers = new DeviceBreakers(meters, DeviceBreakers.defaults());

    @Test
    void breakerStateIsExposedAsMetric() {
        breakers.forDevice("RAD-N");

        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "RAD-N").gauges()).isNotEmpty();
    }

    @Test
    void oneBreakerPerDeviceNamedAfterIt() {
        assertThat(breakers.forDevice("RAD-N")).isSameAs(breakers.forDevice("RAD-N"));
        assertThat(breakers.forDevice("RAD-N")).isNotSameAs(breakers.forDevice("RAD-E"));
        assertThat(breakers.forDevice("RAD-E").getName()).isEqualTo("RAD-E");
        assertThat(breakers.forDevice("RAD-E").getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(5);
    }

    @Test
    void defaultsFollowTheArchitectureReview() {
        CircuitBreakerConfig c = DeviceBreakers.defaults();

        assertThat(c.getSlidingWindowType()).isEqualTo(SlidingWindowType.COUNT_BASED);
        assertThat(c.getSlidingWindowSize()).isEqualTo(5);
        assertThat(c.getMinimumNumberOfCalls()).isEqualTo(3);
        assertThat(c.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(c.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
        assertThat(c.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(1);
        assertThat(c.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
        assertThat(c.getRecordExceptionPredicate().test(new SnmpRequestException(Kind.TIMEOUT, "x"))).isTrue();
        assertThat(c.getRecordExceptionPredicate().test(new IllegalStateException("x"))).isFalse();
    }
}
