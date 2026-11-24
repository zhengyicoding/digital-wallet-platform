package com.acme.bank;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(tracing = true)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class BankSimulatorTest {
    static final BlockingQueue<Delivery> received=new LinkedBlockingQueue<>();
    record Delivery(byte[] body,String timestamp,String signature,long time,String traceparent) {}
    static final HttpServer receiver;
    static {
        try {
            receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            receiver.createContext("/hook", exchange -> {
                received.add(new Delivery(exchange.getRequestBody().readAllBytes(),
                        exchange.getRequestHeaders().getFirst("X-Bank-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-Bank-Signature"),System.nanoTime(),exchange.getRequestHeaders().getFirst("traceparent")));
                exchange.sendResponseHeaders(503,-1); exchange.close();
            });
            receiver.start();
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("bank.api-key",()->"test-key"); r.add("bank.webhook-secret",()->"test-secret");
        r.add("bank.webhook-url",()->"http://127.0.0.1:"+receiver.getAddress().getPort()+"/hook");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    final HttpClient client=HttpClient.newHttpClient();
    @AfterAll static void stop() { receiver.stop(0); }
    @BeforeEach void reset() throws Exception { call("POST","/bank/admin/reset",null,true); received.clear(); }
    HttpResponse<String> call(String method,String path,String body,boolean key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(35))
                .header("Content-Type","application/json");
        if(key) request.header("X-Api-Key","test-key");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode node(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    void config(String body) throws Exception { assertThat(call("PUT","/bank/admin/config",body,true).statusCode()).isEqualTo(200); }
    HttpResponse<String> submit(String reference) throws Exception {
        return call("POST","/bank/v1/transactions","{\"reference\":\""+reference+"\",\"type\":\"CHARGE\",\"amount\":100}",true);
    }
    @Test void bnk5_apiKeyAndBnk3MissingReference() throws Exception {
        assertThat(call("GET","/bank/v1/transactions/missing",null,false).statusCode()).isEqualTo(401);
        assertThat(call("POST","/bank/v1/transactions","{}",false).statusCode()).isEqualTo(401);
        var wrong=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/bank/v1/transactions/x")).header("X-Api-Key","wrong").build();
        assertThat(client.send(wrong,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(call("GET","/bank/v1/transactions/missing",null,true).statusCode()).isEqualTo(404);
    }
    @Test void bnk1_sec5ValidationAndBnk6Defaults() throws Exception {
        // SEC-5: reject malformed types and oversized references before touching the bank store.
        for (String amount : List.of("\"1\"", "\"bad\"", "true", "[]", "{}", "-1", "1.0", "1e2")) {
            var response = call("POST", "/bank/v1/transactions",
                    "{\"reference\":\"x\",\"type\":\"CHARGE\",\"amount\":" + amount + "}", true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(node(response).path("code").asText()).isEqualTo("VALIDATION_FAILED");
        }
        for (String reference : List.of("123", "\"" + "a".repeat(129) + "\"")) {
            assertThat(call("POST", "/bank/v1/transactions", "{\"reference\":" + reference
                    + ",\"type\":\"CHARGE\",\"amount\":1}", true).statusCode()).isEqualTo(400);
        }
        for(String body:List.of("{}","{\"reference\":\"x\",\"type\":\"OTHER\",\"amount\":1}",
                "{\"reference\":\"x\",\"type\":\"CHARGE\",\"amount\":0}",
                "{\"reference\":\"x\",\"type\":\"CHARGE\",\"amount\":1.5}",
                "{\"reference\":\"x\",\"type\":\"CHARGE\",\"amount\":9223372036854775808}")) {
            assertThat(call("POST","/bank/v1/transactions",body,true).statusCode()).isEqualTo(400);
        }
        for(String body:List.of("{\"hangRate\":1.1}","{\"declineRate\":-1}","{\"responseDelayMs\":-1}","{\"settleDelayMs\":-1}","{\"webhookCopies\":-1}"))
            assertThat(call("PUT","/bank/admin/config",body,true).statusCode()).isEqualTo(400);
        config("{}");
        assertThat(node(call("GET","/bank/admin/config",null,true))).isEqualTo(json.readTree("{\"down\":false,\"responseDelayMs\":50,\"hangRate\":0.0,\"settleDelayMs\":500,\"declineRate\":0.0,\"webhookCopies\":1}"));
    }
    @Test void bnk2ConcurrentReplaySchedulesOneSettlementAndConflicts() throws Exception {
        config("{\"responseDelayMs\":0,\"settleDelayMs\":500,\"webhookCopies\":1}");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            List<Future<HttpResponse<String>>> requests=new ArrayList<>();
            for(int i=0;i<20;i++) requests.add(pool.submit(()->{start.await(); return submit("same");}));
            start.countDown(); int created=0; Set<String> ids=new HashSet<>();
            for(var request:requests) { var r=request.get(5,TimeUnit.SECONDS); if(r.statusCode()==202) created++; else assertThat(r.statusCode()).isEqualTo(200); ids.add(node(r).get("bankTransactionId").asText()); }
            assertThat(created).isEqualTo(1); assertThat(ids).hasSize(1);
        }
        for(String change:List.of("\"CHARGE\",\"amount\":101","\"PAYOUT\",\"amount\":100")) {
            var conflict=call("POST","/bank/v1/transactions","{\"reference\":\"same\",\"type\":"+change+"}",true);
            assertThat(conflict.statusCode()).isEqualTo(409); assertThat(node(conflict).get("code").asText()).isEqualTo("REFERENCE_CONFLICT");
        }
        await().atMost(Duration.ofSeconds(4)).untilAsserted(()-> assertThat(received).hasSize(1));
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(received).hasSize(1));
        assertThat(node(submit("same")).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(node(call("GET","/bank/admin/transactions",null,true)).size()).isEqualTo(1);
    }
    @Test void bnk1Bnk4Sec4DeclinedPayoutSignedCopiesWithoutRetries() throws Exception {
        config("{\"settleDelayMs\":20,\"declineRate\":1,\"webhookCopies\":3}");
        var created=call("POST","/bank/v1/transactions","{\"reference\":\"decline\",\"type\":\"PAYOUT\",\"amount\":100}",true);
        assertThat(created.statusCode()).isEqualTo(202); assertThat(node(created).get("status").asText()).isEqualTo("PENDING");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(received).hasSize(3));
        long previous=0;
        for(Delivery d:received) {
            var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((d.timestamp()+".").getBytes(StandardCharsets.UTF_8));
            assertThat(d.signature()).isEqualTo(HexFormat.of().formatHex(mac.doFinal(d.body())));
            assertThat(json.readTree(d.body()).get("reason").asText()).isEqualTo("DECLINED");
            assertThat(json.readTree(d.body()).get("status").asText()).isEqualTo("FAILED");
            if(previous!=0) assertThat(Duration.ofNanos(d.time()-previous).toMillis()).isGreaterThanOrEqualTo(50);
            previous=d.time();
        }
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(received).hasSize(3));
    }
    @Test void bnk6DownAndResponseDelay() throws Exception {
        config("{\"down\":true,\"responseDelayMs\":5000}");
        long start=System.nanoTime(); assertThat(submit("down").statusCode()).isEqualTo(503);
        assertThat(call("GET","/bank/v1/transactions/x",null,true).statusCode()).isEqualTo(503);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
        assertThat(node(call("GET","/bank/admin/transactions",null,true)).size()).isZero();
        config("{\"responseDelayMs\":150}"); start=System.nanoTime();
        call("GET","/bank/v1/transactions/x",null,true);
        assertThat(Duration.ofNanos(System.nanoTime()-start).toMillis()).isGreaterThanOrEqualTo(150);
    }
    @Test void bnk6HangSettlesWithZeroWebhooksBeforePostResponse() throws Exception {
        config("{\"hangRate\":1,\"settleDelayMs\":20,\"webhookCopies\":0,\"responseDelayMs\":0}");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            long start=System.nanoTime(); var pending=pool.submit(()->submit("hang"));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(node(call("GET","/bank/v1/transactions/hang",null,true)).get("status").asText()).isEqualTo("SUCCEEDED"));
            assertThat(pending.isDone()).isFalse();
            assertThat(pending.get(32,TimeUnit.SECONDS).statusCode()).isEqualTo(202);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isGreaterThanOrEqualTo(Duration.ofSeconds(30));
            assertThat(received).isEmpty();
        }
    }
    @Test void bnk6ResetInvalidatesQueuedSettlementAndReferenceReuse() throws Exception {
        config("{\"settleDelayMs\":300,\"declineRate\":1}"); submit("reset");
        call("POST","/bank/admin/reset",null,true);
        assertThat(node(call("GET","/bank/admin/config",null,true)).get("settleDelayMs").asInt()).isEqualTo(500);
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2)).untilAsserted(()-> {
            assertThat(node(call("GET","/bank/admin/transactions",null,true)).size()).isZero(); assertThat(received).isEmpty();
        });
        config("{\"settleDelayMs\":20,\"webhookCopies\":0}"); submit("reset");
        await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(node(call("GET","/bank/v1/transactions/reset",null,true)).get("status").asText()).isEqualTo("SUCCEEDED"));
    }
    @Test void bnkDocs() throws Exception {
        assertThat(call("GET","/docs/bank/",null,false).statusCode()).isEqualTo(302);
        assertThat(call("GET","/docs/bank/v3/api-docs",null,false).body()).contains("/bank/v1/transactions");
    }
    @Test void ops5_ops6_asyncWebhookUsesInstrumentedClientAndW3cContext() throws Exception {
        config("{\"settleDelayMs\":50,\"responseDelayMs\":0}");
        String trace = "1234567890abcdef1234567890abcdef";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/bank/v1/transactions"))
                .header("Content-Type", "application/json").header("X-Api-Key", "test-key")
                .header("traceparent", "00-"+trace+"-1234567890abcdef-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reference\":\"trace-check\",\"type\":\"CHARGE\",\"amount\":100}"))
                .build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(202);
        var delivery = received.poll(5, TimeUnit.SECONDS);
        assertThat(delivery).isNotNull();
        assertThat(delivery.traceparent()).matches("00-"+trace+"-[0-9a-f]{16}-01")
                .doesNotContain("-1234567890abcdef-01");
        call("GET", "/bank/v1/transactions/trace-check", null, true);
        String metrics = call("GET", "/actuator/prometheus", null, false).body();
        assertThat(metrics).contains("http_server_requests_seconds_bucket", "uri=\"/bank/v1/transactions/{reference}\"")
                .doesNotContain("uri=\"/bank/v1/transactions/trace-check\"");
    }

}
