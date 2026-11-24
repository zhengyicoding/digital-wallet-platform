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
    private final URI transactions;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper json;
    private final BankOperations operations;

    BankClient(@Value("${bank.base-url}") URI base, @Value("${bank.api-key}") String apiKey,
               @Value("${bank.timeout}") Duration timeout, ObjectMapper json, BankOperations operations) {
        this.transactions = base.resolve("/bank/v1/transactions");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.json = json;
        this.operations = operations;
        client = HttpClient.newBuilder().connectTimeout(timeout).build();
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
        workers.execute(() -> {
            try {
                if (lookup) {
                    var get = request(URI.create(transactions + "/" + submission.reference())).GET().build();
                    var reply = exchange(get);
                    if (reply.statusCode() != 404) {
                        apply(submission, reply);
                        return;
                    }
                }
                var post = request(transactions).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(submission))).build();
                apply(submission, exchange(post));
            } catch (CallNotPermittedException open) {
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

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).header("X-Api-Key", apiKey).timeout(timeout);
    }

    private void apply(Submission submission, HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() / 100 != 2) return;
        var result = json.readValue(response.body(), BankResult.class);
        if (submission.reference().equals(result.reference()) && result.status() != BankOperations.Status.PENDING) {
            operations.applyResult(result);
        }
    }

    private HttpResponse<byte[]> exchange(HttpRequest request) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return circuit.executeCallable(() -> {
                    var reply = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (reply.statusCode() >= 500) throw new IOException("Bank unavailable");
                    return reply;
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
        client.close();
    }
}
