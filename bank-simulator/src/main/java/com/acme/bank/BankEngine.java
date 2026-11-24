package com.acme.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class BankEngine {
    public enum Type { CHARGE, PAYOUT }
    public record Submission(@NotBlank @Size(max=128) String reference, @NotNull Type type,
                             @NotNull @Min(1) Long amount) {}
    public record Transaction(String bankTransactionId, String reference, Type type, long amount,
                              String status, String reason) {}
    public record Config(Boolean down, @Min(0) Long responseDelayMs,
                         @DecimalMin("0") @DecimalMax("1") Double hangRate,
                         @Min(0) Long settleDelayMs,
                         @DecimalMin("0") @DecimalMax("1") Double declineRate,
                         @Min(0) Integer webhookCopies) {
        public Config {
            down = down == null ? false : down;
            responseDelayMs = responseDelayMs == null ? 50L : responseDelayMs;
            hangRate = hangRate == null ? 0 : hangRate;
            settleDelayMs = settleDelayMs == null ? 500L : settleDelayMs;
            declineRate = declineRate == null ? 0 : declineRate;
            webhookCopies = webhookCopies == null ? 1 : webhookCopies;
        }
    }
    public record Created(Transaction transaction, boolean fresh) {}
    private final LinkedHashMap<String, Transaction> transactions = new LinkedHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2);
    private final ExecutorService deliveries = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final RestClient webhookClient;
    private final Tracer tracer;
    private final ObjectMapper json;
    private final String secret;
    private final URI webhook;
    private Config config = new Config(null,null,null,null,null,null);
    private long generation;
    public BankEngine(ObjectMapper json, @Value("${bank.webhook-secret}") String secret,
                      @Value("${bank.webhook-url}") URI webhook, RestClient.Builder builder, Tracer tracer) {
        this.tracer = tracer;
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.webhookClient = builder.requestFactory(factory).build();
        this.json=json; this.secret=secret; this.webhook=webhook;
        scheduler.setRemoveOnCancelPolicy(true);
    }
    public synchronized Config config() { return config; }
    public synchronized Config configure(Config value) { return config=value; }
    public synchronized List<Transaction> list() { return new ArrayList<>(transactions.values()).reversed(); }
    public synchronized void reset() {
        generation++; transactions.clear(); scheduler.getQueue().clear();
        config=new Config(null,null,null,null,null,null);
    }
    public synchronized Transaction get(String reference) {
        var result=transactions.get(reference);
        if(result==null) throw new BankProblems.Failure(404,"NOT_FOUND");
        return result;
    }
    // BNK-2: creation and scheduling share the same monitor, including reset.
    public synchronized Created submit(Submission input, Config settings) {
        var existing=transactions.get(input.reference());
        if(existing!=null) {
            if(existing.type()!=input.type() || existing.amount()!=input.amount())
                throw new BankProblems.Failure(409,"REFERENCE_CONFLICT");
            return new Created(existing,false);
        }
        var pending=new Transaction("bk-"+UUID.randomUUID(),input.reference(),input.type(),input.amount(),"PENDING",null);
        transactions.put(input.reference(),pending);
        long epoch=generation;
        var parent = tracer.currentSpan();
        scheduler.schedule(() -> settle(pending,settings,epoch,parent),settings.settleDelayMs(),TimeUnit.MILLISECONDS);
        return new Created(pending,true);
    }
    private synchronized void settle(Transaction pending, Config settings, long epoch, Span parent) {
        if(epoch!=generation || transactions.get(pending.reference())!=pending) return;
        boolean declined=ThreadLocalRandom.current().nextDouble()<settings.declineRate();
        var settled=new Transaction(pending.bankTransactionId(),pending.reference(),pending.type(),pending.amount(),
                declined?"FAILED":"SUCCEEDED",declined?"DECLINED":null);
        transactions.put(pending.reference(),settled);
        deliveries.submit(() -> {
            try (var ignored = tracer.withSpan(parent)) {
                deliver(settled,settings.webhookCopies(),epoch);
            }
        });
    }
    private void deliver(Transaction transaction,int copies,long epoch) {
        try {
            byte[] body=json.writeValueAsBytes(transaction);
            for(int i=0;i<copies;i++) {
                if(i>0) pause(ThreadLocalRandom.current().nextLong(50,201));
                synchronized(this) { if(epoch!=generation) return; }
                String timestamp=Long.toString(Instant.now().getEpochSecond());
                Mac mac=Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));
                String signature=HexFormat.of().formatHex(mac.doFinal(body));
                try {
                    webhookClient.post().uri(webhook).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .header("X-Bank-Timestamp",timestamp).header("X-Bank-Signature",signature).body(body)
                            .exchange((request, response) -> response.getStatusCode().value());
                } catch (ResourceAccessException ignored) { /* BNK-4: no delivery retry; SEC-7: never log signed requests. */ }
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(Exception e) { throw new IllegalStateException("Webhook encoding failed",e); }
    }
    static void pause(long milliseconds) throws InterruptedException { Thread.sleep(milliseconds); }
    @PreDestroy void close() { scheduler.shutdownNow(); deliveries.shutdownNow(); client.close(); }
}
