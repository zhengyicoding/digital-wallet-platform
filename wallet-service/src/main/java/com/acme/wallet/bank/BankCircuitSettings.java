package com.acme.wallet.bank;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bank circuit breaker (RES-3). Every exception thrown by a bank call counts as a failure: timeouts, connection
 * errors and 5xx replies (all surfaced as IOException), and spring-web's raw CancellationException on a read
 * timeout. 2xx and 4xx replies return normally and count as successes.
 */
@ConfigurationProperties("bank.circuit")
public record BankCircuitSettings(
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("15s") Duration waitDurationInOpenState,
        @DefaultValue("2") int permittedCallsInHalfOpenState,
        @DefaultValue("true") boolean automaticTransitionToHalfOpen) {

    CircuitBreakerConfig toConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(waitDurationInOpenState)
                .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpenState)
                .automaticTransitionFromOpenToHalfOpenEnabled(automaticTransitionToHalfOpen)
                .recordException(failure -> true)
                .build();
    }
}
