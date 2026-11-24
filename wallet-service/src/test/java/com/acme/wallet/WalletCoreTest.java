package com.acme.wallet;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import javax.sql.DataSource;

import com.acme.wallet.idempotency.Idempotency;
import com.acme.wallet.ledger.Wallets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(tracing = true)
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=32")
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Testcontainers
@DirtiesContext
class WalletCoreTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static HttpServer jwks;
    static RSAKey key;
    static final ConcurrentMap<String, String> bankTraceparents = new ConcurrentHashMap<>();
    static final ConcurrentMap<String, List<JsonNode>> bankCalls = new ConcurrentHashMap<>();
    static final CountDownLatch releaseSlowPayout = new CountDownLatch(1);
    static final ConcurrentMap<String, ObjectNode> bankRecords = new ConcurrentHashMap<>();
    static final ConcurrentMap<String, Integer> lookups = new ConcurrentHashMap<>();
    static final AtomicBoolean outage = new AtomicBoolean();
    static final CountDownLatch delayedReply = new CountDownLatch(1);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    @Autowired TransactionTemplate tx;
    @Autowired Idempotency idempotency;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test").generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/.well-known/jwks.json", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwks.createContext("/bank/v1/transactions", exchange -> {
            try (exchange) {
                var mapper = new ObjectMapper();
                if (!"test-api-key".equals(exchange.getRequestHeaders().getFirst("X-Api-Key"))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                if (exchange.getRequestMethod().equals("GET")) {
                    String path = exchange.getRequestURI().getPath();
                    String reference = path.substring(path.lastIndexOf('/') + 1);
                    lookups.merge(reference, 1, Integer::sum);
                    if (outage.get()) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                    var result = bankRecords.get(reference);
                    if (result == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        byte[] reply = mapper.writeValueAsBytes(result);
                        exchange.sendResponseHeaders(200, reply.length);
                        exchange.getResponseBody().write(reply);
                    }
                    return;
                }
                var request = (ObjectNode) mapper.readTree(exchange.getRequestBody());
                bankCalls.computeIfAbsent(request.path("reference").asText(), ignored -> new CopyOnWriteArrayList<>())
                        .add(request);
                bankTraceparents.put(request.path("reference").asText(), Objects.toString(exchange.getRequestHeaders().getFirst("traceparent"), ""));
                long amount = request.required("amount").asLong();
                boolean outagePayout = amount >= 9101 && amount <= 9103;
                if (outagePayout && outage.get()) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                var record = bankRecords.computeIfAbsent(request.required("reference").asText(),
                        ref -> request.deepCopy().put("status", outagePayout ? "SUCCEEDED" : "PENDING"));
                if (amount == 9111 || amount == 71) {
                    var release = amount == 9111 ? delayedReply : releaseSlowPayout;
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.sendResponseHeaders(503, -1);
                } else {
                    byte[] reply = mapper.writeValueAsBytes(record);
                    exchange.sendResponseHeaders(201, reply.length);
                    exchange.getResponseBody().write(reply);
                }
            }
        });
        jwks.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        jwks.start();
        registry.add("bank.base-url", () -> "http://127.0.0.1:" + jwks.getAddress().getPort());
        registry.add("wallet.reconciliation.interval", () -> "250ms");
        registry.add("wallet.reconciliation.pending-age", () -> "60s");
        registry.add("bank.timeout", () -> "500ms");
        registry.add("wallet.jwks-uri", () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/.well-known/jwks.json");
    }

    @AfterAll static void stopKeys() { jwks.stop(0); }

    record Customer(String username, String token, UUID wallet) {}
    record Reply(int status, JsonNode body, String rawBody, String location, String replayed, String retryAfter) {
        void code(int expected, String code) {
            assertThat(status).as(body.toString()).isEqualTo(expected);
            assertThat(body.path("code").asText()).isEqualTo(code);
            assertThat(body.path("traceId").asText()).isNotBlank();
            if (code.equals("VALIDATION_FAILED")) assertThat(body.path("errors").size()).isPositive();
        }
        JsonNode ok(int expected) {
            assertThat(status).as(body.toString()).isEqualTo(expected);
            return body;
        }
    }

    Reply call(MockHttpServletRequestBuilder request, String token) {
        try {
            if (token != null) request.header("Authorization", "Bearer " + token);
            var result = mvc.perform(request).andReturn();
            var response = result.getResponse();
            if (response.getStatus() >= 500) throw new AssertionError("Unexpected server error", result.getResolvedException());
            String rawBody = response.getContentAsString(StandardCharsets.UTF_8);
            return new Reply(response.getStatus(), json.readTree(rawBody), rawBody, response.getHeader("Location"),
                    response.getHeader("Idempotent-Replayed"), response.getHeader("Retry-After"));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    String token(String username, RSAKey signer, String issuer, Instant expiry, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer(issuer)
                .claim("username",username).claim("name","Name " + username).claim("roles", List.of(role));
        if (expiry != null) claims.expirationTime(Date.from(expiry));
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims.build());
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    String userToken(String name) throws Exception {
        return token(name, key, "digital-wallet-auth", Instant.now().plusSeconds(1800), "USER");
    }

    Customer customer(long funding) throws Exception {
        String name = UUID.randomUUID().toString().substring(0, 12).replace('-', '_');
        String token = userToken(name);
        var wallet = UUID.fromString(call(post("/api/v1/wallets"),token).ok(201).path("id").asText());
        if (funding > 0) {
            // A settled funding fixture contributes to both the ledger and INV-5.
            UUID id = Wallets.newId();
            tx.executeWithoutResult(status -> {
                db.sql("""
                        insert into bank_operations(id,wallet_id,kind,amount,status,created_at,completed_at)
                        values (?,?,'DEPOSIT',?,'SUCCEEDED',now(),now())
                        """).params(id,wallet,funding).update();
                db.sql("insert into postings values (?,'DEPOSIT',?)").params(id,id).update();
                db.sql("""
                        insert into ledger_entries(id,posting_id,account_id,type,amount) values
                        (?,?,(select id from accounts where system_name='EXTERNAL_BANK'),'DEPOSIT',?),
                        (?,?,?,'DEPOSIT',?)
                        """).params(Wallets.newId(),id,-funding,Wallets.newId(),id,wallet,funding).update();
            });
        }
        return new Customer(name,token,wallet);
    }

    Reply send(Customer from, Customer to, long amount) {
        return send(from, to, amount, UUID.randomUUID().toString());
    }

    Reply send(Customer from, Customer to, long amount, String key) {
        return call(transferRequest(to, amount, key), from.token);
    }

    MockHttpServletRequestBuilder transferRequest(Customer to, long amount, String key) {
        var request = post("/api/v1/transfers").contentType("application/json").content("""
                {"toWalletId":"%s","amount":%d,"note":"Lunch"}
                """.formatted(to.wallet, amount));
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    static void parallel(int threads, int count, IntConsumer task) throws Exception {
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var start = new CountDownLatch(1);
            var jobs = new ArrayList<Future<Void>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                jobs.add(pool.submit(() -> { start.await(); task.accept(index); return null; }));
            }
            start.countDown();
            for (var job : jobs) job.get(90, TimeUnit.SECONDS);
        }
    }

    long scalar(String sql) { return db.sql(sql).query(Long.class).single(); }

    void invariants() {
        WalletInvariantAssertions.verify(db, 0);
    }

    @Test void acc2_1_opposingTransfers_CON1_CON3() throws Exception {
        List<Customer> wallets = new ArrayList<>();
        for (int i=0;i<10;i++) wallets.add(customer(10000));
        var rng = new Random(7331);
        var senders = new int[2000];
        var recipients = new int[2000];
        for (int i=0;i<2000;i++) {
            senders[i] = rng.nextInt(10);
            recipients[i] = (senders[i] + 1 + rng.nextInt(9)) % 10;
        }
        senders[0]=recipients[1]=0;
        senders[1]=recipients[0]=1;
        parallel(32,2000,i -> send(wallets.get(senders[i]),wallets.get(recipients[i]),1).ok(201));
        invariants();
    }

    @Test void acc2_2_preventOverdraft_CON2() throws Exception {
        var from = customer(10000);
        var to = customer(0);
        var completed = new AtomicLong();
        parallel(20,20,i -> {
            var reply = send(from,to,1000);
            if (reply.status == 201) completed.incrementAndGet();
            else reply.code(422,"INSUFFICIENT_FUNDS");
        });
        assertThat(completed.get()).isEqualTo(10);
        assertThat(call(get("/api/v1/wallets/me"),from.token).ok(200).get("balance").asLong()).isZero();
        invariants();
    }

    @Test void acc2_3_tenSimultaneousRequests_IDM2_IDM6() throws Exception {
        var from = customer(100);
        var to = customer(0);
        try (var pool = Executors.newFixedThreadPool(10); var blocker = dataSource.getConnection()) {
            // Hold the winner inside its transfer until the other nine HTTP requests have answered.
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("select id from wallets where id=? for update")) {
                lock.setObject(1, from.wallet);
                lock.executeQuery().close();
            }
            var replies = new ExecutorCompletionService<Reply>(pool);
            var start = new CyclicBarrier(10);
            for (int i = 0; i < 10; i++) {
                replies.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return send(from, to, 9, "simultaneous");
                });
            }
            for (int i = 0; i < 9; i++) {
                var next = replies.poll(10, TimeUnit.SECONDS);
                assertThat(next).as("duplicate answered while the wallet was locked").isNotNull();
                var reply = next.get();
                reply.code(409, "REQUEST_IN_PROGRESS");
                assertThat(reply.retryAfter).isEqualTo("1");
            }
            blocker.commit();
            var winner = replies.poll(10, TimeUnit.SECONDS).get();
            winner.ok(201);
            assertThat(winner.replayed).isNull();
        }
        assertSingleTransfer(from, to, 9);
        invariants();
    }

    void assertSingleTransfer(Customer from, Customer to, long amount) {
        var id = db.sql("select id from transfers where from_wallet_id=?").param(from.wallet).query(UUID.class).single();
        var posting = db.sql("select id from postings where reference_type='TRANSFER' and reference_id=?")
                .param(id).query(UUID.class).single();
        assertThat(db.sql("select amount from ledger_entries where posting_id=? order by amount")
                .param(posting).query(Long.class).list()).containsExactly(-amount, amount);
        assertThat(call(get("/api/v1/wallets/me"), to.token).ok(200).path("balance").asLong()).isEqualTo(amount);
    }

    @Test void acc2_4_changedAmount_IDM5() throws Exception {
        var from = customer(100);
        var to = customer(0);
        send(from, to, 9, "changed").ok(201);
        send(from, to, 10, "changed").code(422, "IDEMPOTENCY_KEY_REUSED");
        assertSingleTransfer(from, to, 9);
    }

    @Test void acc2_5_retryAfterLostResponse_IDM2_IDM4() throws Exception {
        var from = customer(9);
        var to = customer(0);
        var original = send(from, to, 9, "lost");
        original.ok(201);
        assertThat(original.replayed).isNull();
        // The retry uses only the original request. The first transfer has exhausted the balance.
        var retry = call(post("/api/v1/transfers").header("Idempotency-Key", "lost").contentType("application/json")
                .content("""
                        {"note":"Lunch","amount":9,"toWalletId":"%s"}
                        """.formatted(to.wallet)), from.token);
        assertThat(retry.status).isEqualTo(original.status);
        assertThat(retry.rawBody).isEqualTo(original.rawBody);
        assertThat(retry.location).isEqualTo(original.location);
        assertThat(retry.replayed).isEqualTo("true");
        assertSingleTransfer(from, to, 9);
        var events = payloads(original, "transfer.completed");
        assertThat(events).containsExactly(json.readTree("""
                {"transferId":"%s","amount":9,"currency":"USD","note":"Lunch",
                 "from":{"walletId":"%s","userId":"%s","displayName":"Name %s"},
                 "to":{"walletId":"%s","userId":"%s","displayName":"Name %s"}}
                """.formatted(original.body.required("id").asText(),
                from.wallet, userId(from), from.username, to.wallet, userId(to), to.username)));
    }

    UUID userId(Customer owner) {
        return db.sql("select user_id from wallets where id=?").param(owner.wallet).query(UUID.class).single();
    }

    // EVT-1: compare every committed envelope for this operation, including after API or webhook replay.
    List<JsonNode> payloads(Reply operation, String... types) throws Exception {
        var id = UUID.fromString(operation.body.required("id").asText());
        var rows = db.sql("select event_id,envelope::text from outbox where aggregate_id=? order by sequence_id")
                .param(id).query((rs, n) -> Map.entry(rs.getObject("event_id", UUID.class), rs.getString("envelope"))).list();
        assertThat(rows).hasSize(types.length);
        var result = new ArrayList<JsonNode>();
        for (int i = 0; i < types.length; i++) {
            var envelope = json.readTree(rows.get(i).getValue());
            assertThat(envelope.size()).isEqualTo(6);
            assertThat(envelope.required("eventId").asText()).isEqualTo(rows.get(i).getKey().toString());
            assertThat(envelope.required("aggregateId").asText()).isEqualTo(id.toString());
            assertThat(envelope.required("type").asText()).isEqualTo(types[i]);
            assertThat(envelope.required("version")).isEqualTo(json.getNodeFactory().numberNode(1));
            Instant.parse(envelope.required("occurredAt").textValue());
            result.add(envelope.required("payload"));
        }
        return result;
    }

    void assertBankEvents(Customer owner, Reply operation, String kind, String outcome) throws Exception {
        var requested = json.createObjectNode().put(kind + "Id", operation.body.required("id").asText())
                .put("walletId", owner.wallet.toString()).put("userId", userId(owner).toString())
                .put("currency", "USD");
        requested.set("amount", operation.body.required("amount"));
        var settled = requested.deepCopy();
        settled.put("failureReason", outcome.equals("failed") ? "DECLINED" : null);
        String type = kind + "." + outcome;
        if (kind.equals("deposit")) {
            assertThat(payloads(operation, type)).containsExactly(settled);
        } else {
            assertThat(payloads(operation, "withdrawal.requested", type)).containsExactly(requested, settled);
        }
    }

    @Test void scopedKeysAndPendingSnapshot_IDM2() throws Exception {
        var alice = customer(10);
        var bob = customer(10);
        var to = customer(0);
        String key = "scoped";
        var first = send(alice, to, 5, key).ok(201);
        var second = send(bob, to, 5, key).ok(201);
        assertThat(second.path("id")).isNotEqualTo(first.path("id"));

        // Exercise the shared transaction path with M5's response contract.
        var user = db.sql("select user_id from wallets where id=?").param(alice.wallet).query(UUID.class).single();
        var request = json.createObjectNode().put("amount", 5);
        var id = UUID.randomUUID();
        var operation = json.createObjectNode().put("id", id.toString()).put("status", "PENDING");
        var accepted = idempotency.execute(user, "/api/v1/deposits", key, request, JsonNode.class,
                HttpStatus.ACCEPTED, ignored -> new Idempotency.Resource(id, operation));
        operation.put("status", "SUCCEEDED");
        var retry = idempotency.execute(user, "/api/v1/deposits", key, request, JsonNode.class,
                HttpStatus.ACCEPTED, ignored -> { throw new AssertionError("Re-executed operation"); });
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retry.getBody()).isEqualTo(accepted.getBody());
        assertThat(json.readTree(retry.getBody()).path("status").asText()).isEqualTo("PENDING");
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test void keyLengthsAndReuseAfterRejection_IDM1_IDM3() throws Exception {
        var from = customer(2);
        var to = customer(0);
        for (String invalid : Arrays.asList(null, "", "a".repeat(65))) {
            send(from, to, 1, invalid).code(400, "IDEMPOTENCY_KEY_REQUIRED");
        }
        send(from, to, 3, "a").code(422, "INSUFFICIENT_FUNDS");
        var retried = send(from, to, 1, "a");
        retried.ok(201);
        assertThat(retried.replayed).isNull();
        send(from, to, 1, "a".repeat(64)).ok(201);
    }

    @Test void commitFailureRollsBackTransferKeyAndEvent_IDM3_IDM4_EVT1() throws Exception {
        var from = customer(9);
        var to = customer(0);
        db.sql("create sequence fail_commit_attempt").update();
        db.sql("""
                create function fail_first_commit() returns trigger language plpgsql as $$
                begin
                    if nextval('fail_commit_attempt') = 1 then raise exception 'test rollback'; end if;
                    return null;
                end $$
                """).update();
        db.sql("""
                create constraint trigger test_commit_failure after insert on idempotency_records
                    deferrable initially deferred for each row when (new.request_key='rollback-once')
                    execute function fail_first_commit()
                """).update();
        long before = scalar("select count(*) from outbox");
        var failure = mvc.perform(transferRequest(to, 9, "rollback-once").header("Authorization", "Bearer " + from.token))
                .andReturn().getResponse();
        assertThat(failure.getStatus()).isEqualTo(500);
        assertThat(scalar("select count(*) from outbox")).isEqualTo(before);
        assertThat(scalar("select count(*) from idempotency_records where request_key='rollback-once'")).isZero();
        assertThat(db.sql("select count(*) from transfers where from_wallet_id=?").param(from.wallet)
                .query(Long.class).single()).isZero();
        var retried = send(from, to, 9, "rollback-once");
        retried.ok(201);
        assertThat(retried.replayed).isNull();
        assertSingleTransfer(from, to, 9);
        invariants();
    }

    @Test void acc2_6_dailyLimit_MNY3() throws Exception {
        var from = customer(900000);
        var to = customer(0);
        send(from,to,200000).ok(201);
        send(from,to,200000).ok(201);
        send(from,to,100001).code(422,"DAILY_LIMIT_EXCEEDED");
        var completed = new AtomicLong();
        parallel(12,12,i -> {
            var reply = send(from,to,10000);
            if (reply.status == 201) completed.incrementAndGet();
            else reply.code(422,"DAILY_LIMIT_EXCEEDED");
        });
        assertThat(completed.get()).isEqualTo(10);
        assertThat(call(get("/api/v1/wallets/me"),from.token).ok(200).get("sentToday").asLong()).isEqualTo(500000);
        invariants();
    }

    @Test void acc2_7_transferOwnership_API4_WAL4_TRF3() throws Exception {
        var alice = customer(1250);
        var bob = customer(0);
        var other = customer(0);
        var lookup = call(get("/api/v1/wallets/lookup").param("username",bob.username),alice.token).ok(200);
        assertThat(lookup.size()).isEqualTo(3);
        assertThat(lookup.get("walletId").asText()).isEqualTo(bob.wallet.toString());
        var reply = send(alice,bob,1250);
        var receipt = reply.ok(201);
        assertThat(reply.location).isEqualTo("/api/v1/transfers/" + receipt.get("id").asText());
        assertThat(call(get(reply.location),alice.token).ok(200)).isEqualTo(receipt);
        assertThat(call(get(reply.location),bob.token).ok(200)).isEqualTo(receipt);
        call(get(reply.location),other.token).code(404,"NOT_FOUND");
        call(get("/api/v1/transfers/" + UUID.randomUUID()),other.token).code(404,"NOT_FOUND");
        var entry = call(get("/api/v1/wallets/me/entries?type=TRANSFER_OUT"),alice.token).ok(200).get("items").get(0);
        assertThat(entry.get("counterparty").get("walletId").asText()).isEqualTo(bob.wallet.toString());
        assertThat(entry.get("referenceId")).isEqualTo(receipt.get("id"));
        assertThat(entry.get("note").asText()).isEqualTo("Lunch");
        assertThat(entry.get("balanceAfter").asLong()).isZero();
    }

    @Test void acc2_8_statementDuringWrites_API5() throws Exception {
        var from = customer(5000);
        var to = customer(0);
        for (int i=0;i<27;i++) send(from,to,1).ok(201);
        var original = db.sql("select id from ledger_entries where account_id=? order by created_at desc,id desc")
                .param(to.wallet).query(UUID.class).list();
        List<UUID> seen = new ArrayList<>();
        var request = get("/api/v1/wallets/me/entries").param("limit","4");
        var page = call(request,to.token).ok(200);
        try (var writer = Executors.newSingleThreadExecutor()) {
            var firstCommit = new CountDownLatch(1);
            var writing = writer.submit(() -> {
                for (int i=0;i<35;i++) {
                    send(from,to,1).ok(201);
                    firstCommit.countDown();
                }
            });
            assertThat(firstCommit.await(10,TimeUnit.SECONDS)).isTrue();
            while (true) {
                for (var item : page.get("items")) seen.add(UUID.fromString(item.get("id").asText()));
                if (page.get("nextCursor").isNull()) break;
                page = call(get("/api/v1/wallets/me/entries").param("limit","4")
                        .param("cursor",page.get("nextCursor").asText()),to.token).ok(200);
            }
            writing.get(15,TimeUnit.SECONDS);
        }
        assertThat(seen).containsExactlyElementsOf(original);
        invariants();
    }

    @Test void openingAndSecurity_WAL1_SEC1_API8() throws Exception {
        var token = userToken("opening");
        var created = new AtomicLong();
        parallel(12,12,i -> {
            var result = call(post("/api/v1/wallets"), token);
            if (result.status == 201) created.incrementAndGet();
            else result.ok(200);
        });
        assertThat(created.get()).isOne();
        var me = get("/api/v1/wallets/me");
        call(me,null).code(401,"UNAUTHENTICATED");
        var future = Instant.now().plusSeconds(300);
        for (var invalid : List.of(token("usr", key, "wrong", future, "USER"),
                token("usr",key,"digital-wallet-auth",null,"USER"),
                token("usr",key,"digital-wallet-auth",Instant.now().minusSeconds(1),"USER"),
                token("usr",new RSAKeyGenerator(2048).generate(),"digital-wallet-auth",future,"USER"))) {
            call(get("/api/v1/wallets/me"),invalid).code(401,"UNAUTHENTICATED");
        }
        call(get("/api/v1/wallets/me"), token("usr",key,"digital-wallet-auth",future,"ADMIN")).code(403,"FORBIDDEN");
        assertThat(call(get("/docs/wallet/v3/api-docs"),null).ok(200).path("paths").has("/api/v1/transfers")).isTrue();
        assertThat(mvc.perform(get("/docs/wallet/")).andReturn().getResponse().getRedirectedUrl()).endsWith("/docs/wallet/ui");
        invariants();
    }

    @Test void databaseGuards_LED1_LED3() throws Exception {
        var owner = customer(100);
        assertThatThrownBy(() -> db.sql("update accounts set balance=0 where id=?").param(owner.wallet).update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from ledger_entries where account_id=?").param(owner.wallet).update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var id = Wallets.newId();
            db.sql("insert into postings values (?,'TEST',?)").params(id,id).update();
            db.sql("insert into ledger_entries(id,posting_id,account_id,type,amount) values (?,?,?,'DEPOSIT',1)")
                    .params(Wallets.newId(),id,owner.wallet).update();
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("Posting requires two balanced entries");
        invariants();
    }

    @Test void retryAbortedTransactions_CON3_TRF1() throws Exception {
        var from = customer(100);
        var to = customer(0);
        db.sql("create sequence retry_attempt").update();
        // Sequences survive rollbacks. Fail the credit leg three times after the debit has been applied.
        db.sql("""
                create function abort_first_three() returns trigger language plpgsql as $$
                declare attempt bigint := nextval('retry_attempt');
                begin
                    if attempt <= 2 then raise exception 'retry' using errcode='40P01'; end if;
                    if attempt = 3 then raise exception 'retry' using errcode='40001'; end if;
                    return new;
                end $$
                """).update();
        db.sql("""
                create trigger test_retry after insert on ledger_entries for each row
                when (new.type='TRANSFER_IN' and new.note='retry fixture') execute function abort_first_three()
                """).update();
        var response = call(post("/api/v1/transfers").header("Idempotency-Key","retry").contentType("application/json")
                .content("{\"toWalletId\":\"" + to.wallet + "\",\"amount\":9,\"note\":\"retry fixture\"}"),from.token);
        response.ok(201);
        assertThat(scalar("select last_value from retry_attempt")).isEqualTo(4);
        assertThat(scalar("select count(*) from transfers where note='retry fixture'")).isOne();
        assertThat(call(get("/api/v1/wallets/me"),from.token).ok(200).path("balance").asLong()).isEqualTo(91);
        invariants();
    }

    @Test void validation_MNY2_SEC5_API6() throws Exception {
        var sender = customer(5);
        for (String invalid : List.of("-1","0","1.0","1.5","200001","\"1\"","null","9223372036854775808","true","{}","[]","1e2","1x")) {
            call(post("/api/v1/transfers").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                    .content("{\"toWalletId\":\"" + UUID.randomUUID() + "\",\"amount\":" + invalid + "}"),sender.token)
                    .code(400,"VALIDATION_FAILED");
        }
        call(post("/api/v1/transfers").contentType("application/json").content("{}"),sender.token).code(400,"IDEMPOTENCY_KEY_REQUIRED");
        var recipient = customer(0);
        String body = "{\"toWalletId\":\"" + recipient.wallet + "\",\"amount\":1";
        for (String badNote : List.of("true", "123", "\"" + "a".repeat(141) + "\"")) {
            call(post("/api/v1/transfers").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                    .content(body + ",\"note\":" + badNote + "}"), sender.token).code(400,"VALIDATION_FAILED");
        }
        call(post("/api/v1/transfers").header("Idempotency-Key","optional-note").contentType("application/json")
                .content(body + "}"), sender.token).ok(201);
        send(sender,sender,1).code(422,"SAME_WALLET");
        send(sender,new Customer("", "",UUID.randomUUID()),1).code(422,"RECIPIENT_NOT_FOUND");
        for (String query : List.of("limit=0","limit=101","limit=x","cursor=!","type=UNKNOWN")) {
            call(get("/api/v1/wallets/me/entries?" + query),sender.token).code(400,"VALIDATION_FAILED");
        }
    }
    @Test void hardeningBankAmountsAndOwnership_SEC5_ACC2_7() throws Exception {
        var owner = customer(1000);
        var other = customer(0);
        for (String endpoint : List.of("deposits", "withdrawals")) {
            for (String invalid : List.of("-1", "0", "1.0", "1.5", "200001", "\"1\"", "null",
                    "9223372036854775808", "true", "{}", "[]", "1e2", "1x")) {
                call(post("/api/v1/" + endpoint).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("{\"amount\":" + invalid + "}"), owner.token)
                        .code(400, "VALIDATION_FAILED");
            }
            var operation = create(owner, endpoint, 10, UUID.randomUUID().toString());
            operation.ok(202);
            call(get(operation.location), other.token).code(404, "NOT_FOUND");
            call(get(operation.location), owner.token).ok(200);
        }
        invariants();
    }

    Reply create(Customer owner, String endpoint, long amount, String key) {
        return call(post("/api/v1/" + endpoint).contentType("application/json").header("Idempotency-Key", key)
                .content("{\"amount\":" + amount + "}"), owner.token);
    }

    Reply webhook(Reply operation, String type, String status, long age, String secret) {
        try {
            String body = """
                    { "reference": "%s", "bankTransactionId":"bank-test", "amount": %d,
                      "type":"%s", "status":"%s", "reason":%s }
                    """.formatted(operation.body.path("id").asText(), operation.body.path("amount").asLong(),
                    type, status, status.equals("FAILED") ? "\"DECLINED\"" : "null");
            String timestamp = Long.toString(Instant.now().getEpochSecond() - age);
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
            return call(post("/internal/webhooks/bank").contentType("application/json").content(body)
                    .header("X-Bank-Timestamp", timestamp).header("X-Bank-Signature", signature), null);
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    void funds(Customer owner, long balance, long reserved, long sent) {
        var wallet = call(get("/api/v1/wallets/me"), owner.token).ok(200);
        assertThat(wallet.required("balance").asLong()).isEqualTo(balance);
        assertThat(wallet.required("reserved").asLong()).isEqualTo(reserved);
        assertThat(wallet.required("sentToday").asLong()).isEqualTo(sent);
    }

    @Test void acc2_9_tripleDeposit_DEP1_DEP3_RES6_IDM2() throws Exception {
        var owner = customer(0);
        var original = create(owner, "deposits", 90, "deposit");
        assertThat(original.ok(202).required("status").asText()).isEqualTo("PENDING");
        assertThat(payloads(original)).isEmpty();
        funds(owner, 0, 0, 0);
        assertThat(call(get("/api/v1/wallets/me/entries"), owner.token).ok(200).required("items")).isEmpty();
        String id = original.body.required("id").asText();
        org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> bankCalls.containsKey(id));
        assertThat(bankCalls.get(id).getFirst().required("type").asText()).isEqualTo("CHARGE");
        parallel(3, 3, i -> webhook(original, "CHARGE", "SUCCEEDED", 0, "test-webhook-secret").ok(200));
        funds(owner, 90, 0, 0);
        assertThat(call(get(original.location), owner.token).ok(200).required("status").asText()).isEqualTo("SUCCEEDED");
        var replay = create(owner, "deposits", 90, "deposit");
        replay.ok(202);
        assertThat(replay.rawBody).isEqualTo(original.rawBody);
        assertThat(replay.replayed).isEqualTo("true");
        assertBankEvents(owner, original, "deposit", "succeeded");
        invariants();
    }

    @Test void acc2_10_authenticateBeforeSettlement_RES9_SEC4() throws Exception {
        var owner = customer(0);
        var original = create(owner, "deposits", 83, "signature");
        webhook(original, "CHARGE", "SUCCEEDED", 0, "wrong-key").code(401, "INVALID_SIGNATURE");
        webhook(original, "CHARGE", "SUCCEEDED", 600, "test-webhook-secret").code(401, "INVALID_SIGNATURE");
        assertThat(call(get(original.location), owner.token).ok(200).path("status").asText()).isEqualTo("PENDING");
        funds(owner, 0, 0, 0);
        webhook(original, "CHARGE", "FAILED", 0, "test-webhook-secret").ok(200);
        assertBankEvents(owner, original, "deposit", "failed");
        assertThat(call(get(original.location), owner.token).ok(200).path("failureReason").asText()).isEqualTo("DECLINED");
        assertThat(call(get("/api/v1/wallets/me/entries"), owner.token).ok(200).path("items")).isEmpty();
        invariants();
    }

    @Test void acc2_14_withdrawSettleAndReverse_WDR1_WDR3_API4_API5() throws Exception {
        var owner = customer(1000);
        var success = create(owner, "withdrawals", 200, "first");
        success.ok(202);
        funds(owner, 800, 200, 200);
        webhook(success, "PAYOUT", "SUCCEEDED", 0, "test-webhook-secret").ok(200);
        funds(owner, 800, 0, 200);
        var declined = create(owner, "withdrawals", 300, "second");
        String reference = declined.ok(202).required("id").asText();
        await().atMost(5, TimeUnit.SECONDS).until(() -> bankRecords.containsKey(reference));
        bankRecords.computeIfPresent(reference, (ref, row) -> row.deepCopy().put("status", "FAILED").put("reason", "DECLINED"));
        call(post("/api/v1/admin/reconciliation"), adminToken()).ok(202);
        await().atMost(5, TimeUnit.SECONDS).until(() ->
                call(get(declined.location), owner.token).ok(200).required("status").asText().equals("FAILED"));
        funds(owner, 800, 0, 200);
        var replay = create(owner, "withdrawals", 300, "second");
        replay.ok(202);
        assertThat(replay.rawBody).isEqualTo(declined.rawBody);
        webhook(declined, "PAYOUT", "FAILED", 0, "test-webhook-secret").ok(200);
        assertBankEvents(owner, success, "withdrawal", "succeeded");
        assertBankEvents(owner, declined, "withdrawal", "failed");
        assertThat(call(get(declined.location), owner.token).ok(200).required("status").asText()).isEqualTo("FAILED");
        var reversal = call(get("/api/v1/wallets/me/entries?type=WITHDRAWAL_REVERSAL"), owner.token)
                .ok(200).required("items").get(0);
        assertThat(reversal.required("amount").asLong()).isEqualTo(300);
        assertThat(reversal.required("balanceAfter").asLong()).isEqualTo(800);
        call(get(declined.location), customer(0).token).code(404, "NOT_FOUND");
        var first = call(get("/api/v1/withdrawals?limit=1"), owner.token).ok(200);
        assertThat(first.required("items").get(0).required("id")).isEqualTo(declined.body.required("id"));
        var next = call(get("/api/v1/withdrawals").param("cursor",first.required("nextCursor").asText()), owner.token).ok(200);
        assertThat(next.required("items").get(0).required("id")).isEqualTo(success.body.required("id"));
        var filtered = call(get("/api/v1/withdrawals?status=FAILED"), owner.token).ok(200);
        assertThat(filtered.required("items").size()).isOne();
        invariants();
    }

    @Test void unknownPayoutRemainsReserved_RES1_RES2_RES4() throws Exception {
        var owner = customer(100);
        try {
            var pending = create(owner, "withdrawals", 71, "unknown");
            String id = pending.ok(202).required("id").asText();
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> bankCalls.getOrDefault(id, List.of()).size() >= 2);
            funds(owner, 29, 71, 71);
            releaseSlowPayout.countDown();
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> bankCalls.get(id).size() == 4);
            assertThat(bankCalls.get(id)).allMatch(body -> body.required("type").asText().equals("PAYOUT"));
            assertThat(call(get(pending.location), owner.token).ok(200).required("status").asText()).isEqualTo("PENDING");
            funds(owner, 29, 71, 71);
            invariants();
        } finally {
            releaseSlowPayout.countDown();
        }
    }

    String adminToken() throws Exception {
        return token("admin", key, "digital-wallet-auth", Instant.now().plusSeconds(1800), "ADMIN");
    }

    void markEligible(Reply operation) {
        db.sql("update bank_operations set created_at=now()-interval '61 seconds' where id=?")
                .param(UUID.fromString(operation.body.required("id").asText())).update();
    }

    @Test void acc2_11_timedOutPayoutRecoveredByPoll_RES4_RES7() throws Exception {
        var owner = customer(10000);
        var op = create(owner, "withdrawals", 9111, "timeout");
        String id = op.ok(202).required("id").asText();
        try {
            await().atMost(8, TimeUnit.SECONDS).until(() -> bankCalls.getOrDefault(id, List.of()).size() >= 2);
            funds(owner, 889, 9111, 9111);
            markEligible(op);
            await().atMost(5, TimeUnit.SECONDS).until(() -> lookups.containsKey(id));
            assertThat(call(get("/api/v1/wallets/me"), owner.token).ok(200).required("reserved").asLong()).isEqualTo(9111);
            bankRecords.computeIfPresent(id, (ref, row) -> row.deepCopy().put("status", "SUCCEEDED"));
            await().atMost(5, TimeUnit.SECONDS).until(() ->
                    call(get(op.location), owner.token).ok(200).required("status").asText().equals("SUCCEEDED"));
            assertBankEvents(owner, op, "withdrawal", "succeeded");
        } finally {
            delayedReply.countDown();
        }
        var wallet = call(get("/api/v1/wallets/me"), owner.token).ok(200);
        assertThat(wallet.required("balance").asLong()).isEqualTo(889);
        assertThat(wallet.required("reserved").asLong()).isZero();
        invariants();
    }

    @Test void acc2_12_webhookRacesReconciliation_RES6_EVT1() throws Exception {
        var owner = customer(0);
        var op = create(owner, "deposits", 522, "race");
        String id = op.ok(202).required("id").asText();
        await().atMost(5, TimeUnit.SECONDS).until(() -> bankRecords.containsKey(id));
        String admin = adminToken();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var webhookResult = tx.execute(status -> {
                db.sql("select id from bank_operations where id=? for update").param(UUID.fromString(id)).query(UUID.class).single();
                bankRecords.computeIfPresent(id, (ref, row) -> row.deepCopy().put("status", "SUCCEEDED"));
                call(post("/api/v1/admin/reconciliation"), admin).ok(202);
                var result = pool.submit(() -> webhook(op, "CHARGE", "SUCCEEDED", 0, "test-webhook-secret").ok(200));
                // Wait until both settlement transactions actually contend for the same operation.
                await().atMost(5, TimeUnit.SECONDS).until(() -> scalar("""
                        select count(*) from pg_stat_activity where wait_event_type='Lock'
                        and query like 'select * from bank_operations where id=%for update%'
                        """) >= 2);
                return result;
            });
            webhookResult.get(5, TimeUnit.SECONDS);
        }
        funds(owner, 522, 0, 0);
        assertBankEvents(owner, op, "deposit", "succeeded");
        invariants();
    }

    @Test void acc2_13_outageThenResubmitUnknownReferences_RES3_RES7() throws Exception {
        var owner = customer(40000);
        var ops = new ArrayList<Reply>();
        outage.set(true);
        try {
            for (int amount = 9101; amount <= 9103; amount++) {
                var op = create(owner, "withdrawals", amount, "outage-" + amount);
                op.ok(202);
                ops.add(op);
            }
            await().atMost(8, TimeUnit.SECONDS).until(() -> ops.stream().allMatch(op ->
                    bankCalls.getOrDefault(op.body.required("id").asText(), List.of()).size() == 4));
            funds(owner, 12694, 27306, 27306);
            ops.forEach(this::markEligible);
            await().atMost(5, TimeUnit.SECONDS).until(() ->
                    ops.stream().allMatch(op -> lookups.containsKey(op.body.required("id").asText())));
            assertThat(call(get("/api/v1/wallets/me"), owner.token).ok(200).required("reserved").asLong()).isEqualTo(27306);
        } finally {
            outage.set(false);
        }
        for (var op : ops) {
            String id = op.body.required("id").asText();
            await().atMost(5, TimeUnit.SECONDS).until(() ->
                    call(get(op.location), owner.token).ok(200).required("status").asText().equals("SUCCEEDED"));
            assertThat(lookups.get(id)).isPositive();
            assertThat(bankCalls.get(id)).hasSize(5).containsOnly(bankCalls.get(id).getFirst());
            assertBankEvents(owner, op, "withdrawal", "succeeded");
        }
        var wallet = call(get("/api/v1/wallets/me"), owner.token).ok(200);
        assertThat(wallet.required("balance").asLong()).isEqualTo(12694);
        assertThat(wallet.required("reserved").asLong()).isZero();
        invariants();
    }

    @Test void administration_ADM1_ADM2_ADM3_ADM4_API5_SEC3() throws Exception {
        var first = customer(0);
        var owner = customer(100);
        var op = create(owner, "withdrawals", 42, "held");
        var admin = adminToken();
        for (String endpoint : List.of("integrity","status","wallets","reconciliation")) {
            String path = "/api/v1/admin/" + endpoint;
            boolean trigger = endpoint.equals("reconciliation");
            call(trigger ? post(path) : get(path), null).code(401, "UNAUTHENTICATED");
            call(trigger ? post(path) : get(path), owner.token).code(403, "FORBIDDEN");
        }
        var report = call(get("/api/v1/admin/integrity"), admin).ok(200);
        assertThat(report.required("ok").asBoolean()).isTrue();
        assertThat(report.required("checks")).hasSize(6);
        assertThat(report.required("totals").required("payoutHold").asLong()).isEqualTo(
                scalar("select balance from accounts where system_name='PAYOUT_HOLD'"));
        var status = call(get("/api/v1/admin/status"), admin).ok(200);
        assertThat(status.required("pendingWithdrawals").asLong()).isEqualTo(
                scalar("select count(*) from bank_operations where status='PENDING' and kind='WITHDRAWAL'"));
        assertThat(status.required("outboxUnsent").asLong()).isEqualTo(scalar("select count(*) from outbox where sent_at is null"));
        assertThat(status.required("pendingDeposits").asLong()).isEqualTo(
                scalar("select count(*) from bank_operations where status='PENDING' and kind='DEPOSIT'"));
        long pendingAge = scalar("""
                select greatest(0, floor(extract(epoch from now()-min(created_at)))) from bank_operations where status='PENDING'
                """);
        long unsentAge = scalar("""
                select greatest(0, floor(extract(epoch from now()-min((envelope->>'occurredAt')::timestamptz))))
                from outbox where sent_at is null
                """);
        assertThat(status.required("oldestPendingSeconds").asLong()).isBetween(Math.max(0, pendingAge - 1), pendingAge);
        assertThat(status.required("outboxOldestSeconds").asLong()).isBetween(Math.max(0, unsentAge - 1), unsentAge);
        assertThat(status.required("bankCircuit").asText()).isEqualTo("CLOSED");
        var page = call(get("/api/v1/admin/wallets?limit=1"), admin).ok(200);
        var row = page.required("items").get(0);
        assertThat(row.required("walletId").asText()).isEqualTo(owner.wallet.toString());
        assertThat(row.required("balance").asLong()).isEqualTo(58);
        assertThat(row.required("reserved").asLong()).isEqualTo(42);
        customer(0);
        var next = call(get("/api/v1/admin/wallets?limit=1").param("cursor", page.required("nextCursor").asText()),admin).ok(200);
        assertThat(next.required("items").get(0).required("walletId").asText()).isEqualTo(first.wallet.toString());
        for (String query : List.of("limit=0","limit=101","cursor=!"))
            call(get("/api/v1/admin/wallets?" + query), admin).code(400, "VALIDATION_FAILED");
        webhook(op, "PAYOUT", "SUCCEEDED", 0, "test-webhook-secret").ok(200);
    }

    @Test void transferRacesWithdrawal_CON2_MNY3() throws Exception {
        var to = customer(0);
        for (boolean daily : List.of(false, true)) {
            var from = customer(daily ? 800000 : 100000);
            if (daily) {
                send(from, to, 200000).ok(201);
                send(from, to, 200000).ok(201);
            }
            Reply[] replies = new Reply[2];
            parallel(2, 2, i -> replies[i] = i == 0 ? send(from, to, 100000)
                    : create(from, "withdrawals", 100000, "race"));
            boolean transferred = replies[0].status == 201;
            replies[transferred ? 0 : 1].ok(transferred ? 201 : 202);
            replies[transferred ? 1 : 0].code(422, daily ? "DAILY_LIMIT_EXCEEDED" : "INSUFFICIENT_FUNDS");
            funds(from, daily ? 300000 : 0, transferred ? 0 : 100000, daily ? 500000 : 100000);
            invariants();
        }
    }
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired com.acme.wallet.bank.BankOperations bankOperations;
    @Autowired com.acme.wallet.admin.StatusQueries statusQueries;

    double operations(String type, String result) {
        return meters.get("wallet.operations").tags("type", type, "result", result).counter().count();
    }

    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @Test void ops4_ops5_committedTransitionsOnlyAndPersistentBacklogGauges(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var owner = customer(5000);
        var other = customer(0);
        double transfers = operations("TRANSFER", "COMPLETED");
        String key = UUID.randomUUID().toString();
        send(owner, other, 100, key).ok(201);
        send(owner, other, 100, key).ok(201);
        send(owner, other, 10000).code(422, "INSUFFICIENT_FUNDS");
        assertThat(operations("TRANSFER", "COMPLETED")).isEqualTo(transfers + 1);
        double pending = operations("DEPOSIT", "PENDING");
        double settled = operations("DEPOSIT", "SUCCEEDED");
        var operation = create(owner, "deposits", 37, UUID.randomUUID().toString());
        operation.ok(202);
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(bankTraceparents.get(operation.body.path("id").asText())).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
        assertThat(operations("DEPOSIT", "PENDING")).isEqualTo(pending + 1);
        UUID id = UUID.fromString(operation.body.path("id").asText());
        var result = new com.acme.wallet.bank.BankOperations.BankResult("bank-test", id, "CHARGE", 37,
                com.acme.wallet.bank.BankOperations.Status.SUCCEEDED, null);
        // OPS-4/5, RES-6: rollback cannot report success; duplicated settlement counts once.
        tx.executeWithoutResult(status -> { bankOperations.applyResult(result); status.setRollbackOnly(); });
        assertThat(operations("DEPOSIT", "SUCCEEDED")).isEqualTo(settled);
        db.sql("update bank_operations set created_at=now()-interval '5 seconds' where id=?").param(id).update();
        var snapshot = statusQueries.snapshot();
        assertThat(meters.get("wallet.pending.operations").tag("type", "DEPOSIT").gauge().value())
                .isEqualTo(snapshot.pendingDeposits());
        assertThat(meters.get("wallet.pending.oldest.age.seconds").tag("type", "DEPOSIT").gauge().value()).isGreaterThanOrEqualTo(5);
        bankOperations.applyResult(result);
        bankOperations.applyResult(result);
        assertThat(operations("DEPOSIT", "SUCCEEDED")).isEqualTo(settled + 1);
        assertThat(meters.get("wallet.outbox.backlog").gauge().value()).isEqualTo(statusQueries.snapshot().outboxUnsent());
        assertThat(meters.get("wallet.outbox.oldest.age.seconds").gauge().value()).isGreaterThanOrEqualTo(0);
        assertThat(meters.get("wallet.bank.circuit.state").tag("state", "CLOSED").gauge().value()).isEqualTo(1);
        var metrics = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("http_server_requests_seconds_bucket", "wallet_bank_calls_total", "wallet_operations_total")
                .doesNotContain("operationId=", "userId=", "reference=");
        var events = output.getOut().lines().filter(line -> line.startsWith("{"))
                .map(line -> { try { return json.readTree(line); } catch (Exception ex) { throw new AssertionError(ex); } })
                .filter(event -> event.path("event").asText().equals("wallet.operation")).toList();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.path("type").asText()).isEqualTo("TRANSFER");
            assertThat(event.path("outcome").asText()).isEqualTo("COMPLETED");
            assertThat(event.path("traceId").asText()).hasSize(32);
            assertThat(event.path("spanId").asText()).hasSize(16);
            assertThat(event.path("userId").asText()).isNotBlank();
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.path("operationId").asText()).isEqualTo(id.toString());
            assertThat(event.path("outcome").asText()).isEqualTo("PENDING");
        });
        invariants();
    }

}
