package com.acme.wallet.bank;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
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
        workers.execute(() -> {
            try {
                submit(submission);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                log.warn("Bank submission for {} ended with {}", submission.reference(), ex.getClass().getSimpleName());
            }
        });
    }

    private void submit(Submission submission) throws Exception {
        var request = HttpRequest.newBuilder(transactions).header("X-Api-Key", apiKey)
                .header("Content-Type", "application/json").timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(submission))).build();
        for (int attempt = 0; ; attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = circuit.executeCallable(() -> {
                    var reply = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (reply.statusCode() >= 500) throw new IOException("Bank unavailable");
                    return reply;
                });
            } catch (CallNotPermittedException open) {
                return;
            } catch (IOException unavailable) {
                if (attempt == 3) throw unavailable;
                long backoff = 100L << attempt;
                Thread.sleep(backoff + ThreadLocalRandom.current().nextLong(backoff));
                continue;
            }
            if (response.statusCode() / 100 == 2) {
                var result = json.readValue(response.body(), BankResult.class);
                if (submission.reference().equals(result.reference()) && result.status() != BankOperations.Status.PENDING) {
                    operations.applyResult(result);
                }
            }
            return;
        }
    }

    @PreDestroy void close() {
        workers.shutdownNow();
        client.close();
    }
}
