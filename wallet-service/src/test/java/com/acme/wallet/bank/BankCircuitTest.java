package com.acme.wallet.bank;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.acme.wallet.WalletInvariantAssertions;
import com.acme.wallet.ledger.Wallets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.StateTransition;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RES-3 / RES-5 / ADM-2: a bank outage opens the circuit with the production thresholds (window 10, minimum 5,
 * 50%, 2 half-open calls). Only the open-state wait and the reconciliation timings are shortened. Every wait is an
 * await on observable state; circuit transitions are recorded from the breaker's own event stream.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "bank.timeout=500ms",
        "bank.circuit.wait-duration-in-open-state=3s",
        "wallet.reconciliation.interval=200ms",
        "wallet.reconciliation.pending-age=0s"
})
@AutoConfigureMockMvc
@DirtiesContext
class BankCircuitTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final AtomicBoolean bankDown = new AtomicBoolean();
    static final Map<String, ObjectNode> bankRecords = new ConcurrentHashMap<>();
    static final Map<String, List<String>> bankCalls = new ConcurrentHashMap<>();
    static HttpServer bank;

    @DynamicPropertySource
    static void bankStub(DynamicPropertyRegistry registry) throws Exception {
        var mapper = new ObjectMapper();
        bank = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bank.createContext("/bank/v1/transactions", exchange -> {
            try (exchange) {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                JsonNode submitted = method.equals("GET") ? null : mapper.readTree(exchange.getRequestBody());
                String reference = submitted == null ? path.substring(path.lastIndexOf('/') + 1)
                        : submitted.required("reference").asText();
                bankCalls.computeIfAbsent(reference, ignored -> new CopyOnWriteArrayList<>()).add(method);
                if (bankDown.get()) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                ObjectNode record = method.equals("GET") ? bankRecords.get(reference) : bankRecords.computeIfAbsent(
                        reference, ref -> mapper.createObjectNode().put("bankTransactionId", "bank-" + ref)
                                .put("reference", ref).put("type", submitted.required("type").asText())
                                .put("amount", submitted.required("amount").asLong())
                                .put("status", "SUCCEEDED"));
                if (record == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] reply = mapper.writeValueAsBytes(record);
                exchange.sendResponseHeaders(method.equals("GET") ? 200 : 201, reply.length);
                exchange.getResponseBody().write(reply);
            }
        });
        bank.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        bank.start();
        registry.add("bank.base-url", () -> "http://127.0.0.1:" + bank.getAddress().getPort());
    }

    @AfterAll static void stopBank() { bank.stop(0); }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired TransactionTemplate tx;
    @Autowired Wallets wallets;
    @Autowired BankClient client;
    @Autowired MeterRegistry meters;
    @Autowired ObjectMapper json;

    private UUID fundedUser(long funding) {
        UUID user = UUID.randomUUID();
        UUID wallet = wallets.open(user, user.toString().substring(0, 10), "Circuit").wallet().id();
        UUID deposit = Wallets.newId();
        tx.executeWithoutResult(status -> {
            db.sql("""
                    insert into bank_operations(id,wallet_id,kind,amount,status,created_at,completed_at)
                    values (?,?,'DEPOSIT',?,'SUCCEEDED',now(),now())
                    """).params(deposit, wallet, funding).update();
            db.sql("insert into postings values (?,'DEPOSIT',?)").params(deposit, deposit).update();
            db.sql("""
                    insert into ledger_entries(id,posting_id,account_id,type,amount) values
                    (?,?,(select id from accounts where system_name='EXTERNAL_BANK'),'DEPOSIT',?),
                    (?,?,?,'DEPOSIT',?)
                    """).params(Wallets.newId(), deposit, -funding, Wallets.newId(), deposit, wallet, funding).update();
        });
        return user;
    }

    private UUID withdraw(UUID user, long amount) throws Exception {
        var response = mvc.perform(post("/api/v1/withdrawals")
                        .with(jwt().jwt(token -> token.subject(user.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"amount\":" + amount + "}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("RES-1: accepted even while the bank is unavailable").isEqualTo(202);
        return UUID.fromString(json.readTree(response.getContentAsByteArray()).required("id").asText());
    }

    private String adminStatusCircuit() throws Exception {
        var response = mvc.perform(get("/api/v1/admin/status")
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsByteArray()).required("bankCircuit").asText();
    }

    private double gauge(String state) {
        return meters.get("wallet.bank.circuit.state").tag("state", state).gauge().value();
    }

    private double circuitOpenRejections() {
        return meters.get("wallet.bank.calls").tag("outcome", "CIRCUIT_OPEN").counter().count();
    }

    private String status(UUID operation) {
        return db.sql("select status from bank_operations where id=?").param(operation).query(String.class).single();
    }

    private long count(String sql, UUID operation) {
        return db.sql(sql).param(operation).query(Long.class).single();
    }

    @Test void outageOpensCircuitThenRecoversAndSettlesOnce_RES3_RES5_ADM2() throws Exception {
        var transitions = new CopyOnWriteArrayList<StateTransition>();
        client.circuitEvents().onStateTransition(event -> transitions.add(event.getStateTransition()));
        assertThat(adminStatusCircuit()).isEqualTo("CLOSED");
        UUID user = fundedUser(10_000);
        var withdrawals = new ArrayList<UUID>();

        // RES-5: 503s are unknown outcomes. Two withdrawals' bounded retries exceed the 5-call minimum at 100% failure.
        bankDown.set(true);
        withdrawals.add(withdraw(user, 101));
        withdrawals.add(withdraw(user, 102));
        await().atMost(Duration.ofSeconds(10)).until(() -> transitions.contains(StateTransition.CLOSED_TO_OPEN));

        // ADM-2: status and the circuit-state metric agree while OPEN.
        assertThat(adminStatusCircuit()).isEqualTo("OPEN");
        assertThat(gauge("OPEN")).isEqualTo(1);
        assertThat(gauge("CLOSED")).isZero();

        // While OPEN, new withdrawals are still accepted and reserved; their bank calls are rejected unsent.
        double rejectedBefore = circuitOpenRejections();
        for (long amount = 103; amount <= 105; amount++) withdrawals.add(withdraw(user, amount));
        await().atMost(Duration.ofSeconds(2)).until(() -> circuitOpenRejections() - rejectedBefore >= 3);
        assertThat(withdrawals.subList(2, 5)).allSatisfy(id -> assertThat(bankCalls).doesNotContainKey(id.toString()));
        assertThat(withdrawals).allSatisfy(id -> assertThat(status(id)).isEqualTo("PENDING"));
        var during = wallets.details(user);
        assertThat(during.reserved()).isEqualTo(101 + 102 + 103 + 104 + 105);
        assertThat(during.balance()).isEqualTo(10_000 - during.reserved());
        assertThat(transitions).containsExactly(StateTransition.CLOSED_TO_OPEN);

        // The bank returns. The breaker moves to HALF_OPEN on its own; reconciliation's successful calls close it.
        bankDown.set(false);
        await().atMost(Duration.ofSeconds(15)).until(() ->
                withdrawals.stream().allMatch(id -> status(id).equals("SUCCEEDED")));
        await().atMost(Duration.ofSeconds(5)).until(() -> transitions.contains(StateTransition.HALF_OPEN_TO_CLOSED));
        assertThat(transitions).containsExactly(StateTransition.CLOSED_TO_OPEN,
                StateTransition.OPEN_TO_HALF_OPEN, StateTransition.HALF_OPEN_TO_CLOSED);
        assertThat(adminStatusCircuit()).isEqualTo("CLOSED");
        assertThat(gauge("CLOSED")).isEqualTo(1);
        assertThat(gauge("OPEN")).isZero();

        // Exactly once: one reserve and one settlement posting, one requested and one succeeded event per withdrawal.
        for (UUID id : withdrawals) {
            assertThat(count("select count(*) from postings where reference_id=?", id)).isEqualTo(2);
            assertThat(count("select count(*) from outbox where aggregate_id=?", id)).isEqualTo(2);
            assertThat(count("select count(*) from outbox where aggregate_id=? and envelope->>'type'='withdrawal.succeeded'", id))
                    .isOne();
        }
        var settled = wallets.details(user);
        assertThat(settled.reserved()).isZero();
        assertThat(settled.balance()).isEqualTo(10_000 - (101 + 102 + 103 + 104 + 105));
        WalletInvariantAssertions.verify(db, 0);
    }
}
