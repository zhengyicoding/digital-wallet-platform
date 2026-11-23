package com.acme.wallet;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import com.acme.wallet.ledger.Wallets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=32")
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Testcontainers
class WalletCoreTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static HttpServer jwks;
    static RSAKey key;
    static final AtomicLong seededDeposits = new AtomicLong();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    @Autowired TransactionTemplate tx;

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
        jwks.start();
        registry.add("wallet.jwks-uri", () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/.well-known/jwks.json");
    }

    @AfterAll static void stopKeys() { jwks.stop(0); }

    record Customer(String username, String token, UUID wallet) {}
    record Reply(int status, JsonNode body, String location) {
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
            return new Reply(response.getStatus(), json.readTree(response.getContentAsByteArray()), response.getHeader("Location"));
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
            // Balanced fixture standing in for M5's succeeded deposit.
            UUID id = Wallets.newId();
            tx.executeWithoutResult(status -> {
                db.sql("insert into postings values (?,'DEPOSIT',?)").params(id,id).update();
                db.sql("""
                        insert into ledger_entries(id,posting_id,account_id,type,amount) values
                        (?,?,(select id from accounts where system_name='EXTERNAL_BANK'),'DEPOSIT',?),
                        (?,?,?,'DEPOSIT',?)
                        """).params(Wallets.newId(),id,-funding,Wallets.newId(),id,wallet,funding).update();
            });
            seededDeposits.addAndGet(funding);
        }
        return new Customer(name,token,wallet);
    }

    Reply send(Customer from, Customer to, long amount) {
        return call(post("/api/v1/transfers").header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content("""
                        {"toWalletId":"%s","amount":%d,"note":"Lunch"}
                        """.formatted(to.wallet, amount)), from.token);
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
        // LED-5, INV-1..6. All writers have joined before reading committed rows.
        assertThat(scalar("select coalesce(sum(amount),0) from ledger_entries")).isZero();
        assertThat(scalar("""
                select count(*) from (select p.id from postings p left join ledger_entries e on p.id=e.posting_id
                group by p.id having count(e.id)<>2 or sum(e.amount)<>0) invalid
                """)).isZero();
        assertThat(scalar("""
                select count(*) from accounts a where balance <>
                (select coalesce(sum(amount),0) from ledger_entries where account_id=a.id)
                """)).isZero();
        assertThat(scalar("select count(*) from accounts where system_name is distinct from 'EXTERNAL_BANK' and balance<0")).isZero();
        assertThat(scalar("select balance from accounts where system_name='PAYOUT_HOLD'")).isZero();
        assertThat(scalar("select balance from accounts where system_name='EXTERNAL_BANK'")).isEqualTo(-seededDeposits.get());
        assertThat(scalar("""
                select count(*) from (select balance_after,sum(amount) over
                (partition by account_id order by e.created_at,e.id) running from ledger_entries e
                join accounts a on a.id=e.account_id where a.wallet_id is not null) s
                where balance_after is distinct from running
                """)).isZero();
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
        for (String invalid : List.of("-1","0","1.0","1.5","200001","\"1\"","null","9223372036854775808")) {
            call(post("/api/v1/transfers").header("Idempotency-Key","x").contentType("application/json")
                    .content("{\"toWalletId\":\"" + UUID.randomUUID() + "\",\"amount\":" + invalid + "}"),sender.token)
                    .code(400,"VALIDATION_FAILED");
        }
        call(post("/api/v1/transfers").contentType("application/json").content("{}"),sender.token).code(400,"IDEMPOTENCY_KEY_REQUIRED");
        var recipient = customer(0);
        String body = "{\"toWalletId\":\"" + recipient.wallet + "\",\"amount\":1";
        for (String badNote : List.of("true", "123", "\"" + "a".repeat(141) + "\"")) {
            call(post("/api/v1/transfers").header("Idempotency-Key","x").contentType("application/json")
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
}
