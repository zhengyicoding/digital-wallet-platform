package com.acme.wallet.bank;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import com.acme.wallet.bank.BankOperations.Submission;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * spring-web 6.2.1 can report a JDK client read timeout as a raw CancellationException instead of an
 * IOException. The fixture fails every attempt before any socket is opened, so no timing is involved; `failure`
 * selects how (raw cancellation by default, a client timeout, or a 503 reply).
 */
@ExtendWith(OutputCaptureExtension.class)
class BankClientTest {
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile String failure = "cancel";
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BankOperations operations = mock(BankOperations.class);
    private final BankClient client = new BankClient(URI.create("http://bank.invalid"), "test-api-key",
            Duration.ofMillis(500), new ObjectMapper(), operations,
            RestClient.builder().requestInterceptor((request, body, execution) -> {
                attempts.incrementAndGet();
                return switch (failure) {
                    case "timeout" -> throw new java.net.http.HttpTimeoutException("read timed out");
                    case "503" -> new org.springframework.mock.http.client.MockClientHttpResponse(
                            new byte[0], org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                    default -> throw new CancellationException();
                };
            }), registry, Tracer.NOOP, new BankCircuitSettings(10, 5, 50, Duration.ofSeconds(15), 2, true));
    private final Submission payout = new Submission(UUID.randomUUID(), "PAYOUT", 73);

    @AfterEach void clearInterrupt() {
        Thread.interrupted();
        client.close();
    }

    private double timeouts() {
        return registry.counter("wallet.bank.calls", "outcome", "TIMEOUT").count();
    }

    @Test void rawCancellationIsUnknownAndRetriedBounded_RES3_RES4() {
        assertThatThrownBy(() -> client.exchange(payout, false))
                .isInstanceOf(IOException.class).hasCauseInstanceOf(CancellationException.class);
        // RES-4: an unknown outcome gets the same four bounded attempts as any other timeout.
        assertThat(attempts).hasValue(4);
        assertThat(timeouts()).isEqualTo(4);
        // RES-3: the circuit counts every cancelled attempt as a failure.
        assertThat(client.circuitMetrics().getNumberOfFailedCalls()).isEqualTo(4);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        // No result is applied, so the operation stays PENDING and its funds stay reserved.
        verify(operations, never()).applyResult(any());
    }

    @Test void interruptedThreadStopsRetryingAndKeepsFlag_RES4_OPS8() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> client.exchange(payout, false)).isInstanceOf(InterruptedException.class);
        assertThat(attempts).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(operations, never()).applyResult(any());
    }

    @Test void shutdownStopsRetrying_RES4_OPS8() {
        client.close();
        assertThatThrownBy(() -> client.exchange(payout, false)).isInstanceOf(InterruptedException.class);
        assertThat(attempts).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(operations, never()).applyResult(any());
    }

    @Test void exhaustedCallCountedAsErrorAndLeftPending_OPS5_RES4(CapturedOutput output) {
        client.afterCommit(payout);
        // Wait on the counter itself; dispatch increments it exactly once when the call is given up.
        await().atMost(Duration.ofSeconds(10)).until(() ->
                registry.counter("wallet.bank.calls", "outcome", "ERROR").count() == 1);
        assertThat(attempts).hasValue(4);
        await().atMost(Duration.ofSeconds(5)).until(() -> output.getOut().contains("Bank call for " + payout.reference()));
        assertThat(output.getOut()).contains("ended with IOException").doesNotContain("test-api-key");
        verify(operations, never()).applyResult(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"timeout", "cancel", "503"})
    void everyUnknownOutcomeIsACircuitFailure_RES3_RES5(String mode) {
        failure = mode;
        assertThatThrownBy(() -> client.exchange(payout, false)).isInstanceOf(IOException.class);
        assertThat(attempts).hasValue(4);
        assertThat(client.circuitMetrics().getNumberOfFailedCalls()).isEqualTo(4);
        assertThat(client.circuitMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(client.circuitState()).isEqualTo("CLOSED");
    }

    @Test void fifthFailureOpensCircuitAndRejectsWithoutCalling_RES3() {
        failure = "503";
        assertThatThrownBy(() -> client.exchange(payout, false)).isInstanceOf(IOException.class);
        // The window needs five calls; the fifth failure opens it and the sixth attempt is rejected unsent.
        assertThatThrownBy(() -> client.exchange(payout, false))
                .isInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
        assertThat(attempts).hasValue(5);
        assertThat(client.circuitState()).isEqualTo("OPEN");
        verify(operations, never()).applyResult(any());
    }
}
