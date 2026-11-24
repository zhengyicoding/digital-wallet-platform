package com.acme.wallet.bank;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import com.acme.wallet.bank.BankOperations.BankResult;
import com.acme.wallet.bank.BankOperations.Submission;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.tracing.Tracer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
class BankClient {
    private static final Logger log = LoggerFactory.getLogger(BankClient.class);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final CircuitBreaker circuit = CircuitBreaker.ofDefaults("bank");
    private final Set<UUID> reconciling = ConcurrentHashMap.newKeySet();
    private final HttpClient transport;
    private final RestClient client;
    private final MeterRegistry registry;
    private final Tracer tracer;
    private final ObjectMapper json;
    private final BankOperations operations;

    BankClient(@Value("${bank.base-url}") URI base, @Value("${bank.api-key}") String apiKey,
               @Value("${bank.timeout}") Duration timeout, ObjectMapper json, BankOperations operations,
               RestClient.Builder builder, MeterRegistry registry, Tracer tracer) {
        this.json = json;
        this.operations = operations;
        this.registry = registry;
        this.tracer = tracer;
        transport = HttpClient.newBuilder().connectTimeout(timeout).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(timeout);
        client = builder.baseUrl(base.toString()).requestFactory(factory).defaultHeader("X-Api-Key", apiKey).build();
        for (var state : CircuitBreaker.State.values()) {
            Gauge.builder("wallet.bank.circuit.state", circuit, c -> c.getState() == state ? 1 : 0)
                    .tag("state", state.name()).register(registry);
        }
        for (String outcome : new String[]{"SUCCESS", "NOT_FOUND", "HTTP_ERROR", "SERVER_ERROR", "TIMEOUT", "CONNECTION_ERROR", "CIRCUIT_OPEN"}) {
            registry.counter("wallet.bank.calls", "outcome", outcome);
        }
    }

    @TransactionalEventListener
    public void afterCommit(Submission submission) {
        dispatch(submission, false);
    }

    void reconcile(Submission submission) {
        if (reconciling.add(submission.reference())) dispatch(submission, true);
    }

    String circuitState() {
        return circuit.getState().name();
    }

    private void dispatch(Submission submission, boolean lookup) {
        var parent = tracer.currentSpan();
        workers.execute(() -> {
            try (var ignored = tracer.withSpan(parent)) {
                if (lookup) {
                    var reply = exchange(submission, true);
                    if (reply.statusCode() != 404) {
                        apply(submission, reply);
                        return;
                    }
                }
                apply(submission, exchange(submission, false));
            } catch (CallNotPermittedException open) {
                registry.counter("wallet.bank.calls", "outcome", "CIRCUIT_OPEN").increment();
                // The next reconciliation poll can retry after the circuit recovers.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                log.warn("Bank call for {} ended with {}", submission.reference(), ex.getClass().getSimpleName());
            } finally {
                if (lookup) reconciling.remove(submission.reference());
            }
        });
    }

    private record Reply(int statusCode, byte[] body) {}

    private void apply(Submission submission, Reply response) throws IOException {
        if (response.statusCode() / 100 != 2) return;
        var result = json.readValue(response.body(), BankResult.class);
        if (submission.reference().equals(result.reference()) && result.status() != BankOperations.Status.PENDING) {
            operations.applyResult(result);
        }
    }

    private Reply exchange(Submission submission, boolean lookup) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return circuit.executeCallable(() -> {
                    try {
                        RestClient.RequestHeadersSpec<?> request = lookup
                                ? client.get().uri("/bank/v1/transactions/{reference}", submission.reference())
                                : client.post().uri("/bank/v1/transactions")
                                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(submission);
                        Reply reply = request.exchange((req, response) ->
                                new Reply(response.getStatusCode().value(), response.getBody().readAllBytes()));
                        String outcome = reply.statusCode >= 500 ? "SERVER_ERROR" : reply.statusCode == 404
                                ? "NOT_FOUND" : reply.statusCode >= 400 ? "HTTP_ERROR" : "SUCCESS";
                        registry.counter("wallet.bank.calls", "outcome", outcome).increment();
                        if (reply.statusCode >= 500) throw new IOException("Bank unavailable");
                        return reply;
                    } catch (ResourceAccessException unavailable) {
                        Throwable cause = unavailable;
                        while (cause.getCause() != null) cause = cause.getCause();
                        registry.counter("wallet.bank.calls", "outcome", cause instanceof java.net.http.HttpTimeoutException
                                || cause instanceof java.net.SocketTimeoutException ? "TIMEOUT" : "CONNECTION_ERROR").increment();
                        throw new IOException("Bank unavailable");
                    }
                });
            } catch (IOException unavailable) {
                if (attempt == 3) throw unavailable;
                long backoff = 100L << attempt;
                Thread.sleep(backoff + ThreadLocalRandom.current().nextLong(backoff));
            }
        }
    }

    @PreDestroy void close() {
        workers.shutdownNow();
        transport.close();
    }
}
