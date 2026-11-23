package com.acme.wallet.bank;

import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;

import com.acme.wallet.WalletInvariantAssertions;
import com.acme.wallet.bank.BankOperations.BankResult;
import com.acme.wallet.bank.BankOperations.Status;
import com.acme.wallet.ledger.Wallets;
import com.acme.wallet.outbox.OutboxRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "wallet.outbox.enabled=true", "wallet.outbox.poll-ms=100", "spring.task.scheduling.pool.size=2",
        "spring.kafka.producer.properties[request.timeout.ms]=1000",
        "spring.kafka.producer.properties[delivery.timeout.ms]=2000"
})
@AutoConfigureMockMvc
@DirtiesContext
class OutboxRelayTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    static final KafkaContainer broker = new KafkaContainer("apache/kafka:3.8.0")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void kafkaAddress(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", broker::getBootstrapServers);
    }

    @TestConfiguration
    static class ConcurrentRelays {
        @Bean
        OutboxRelay secondRelay(JdbcClient db, TransactionTemplate tx, KafkaAdmin admin, KafkaTemplate<String, String> kafka) {
            return new OutboxRelay(db, tx, admin, kafka);
        }
    }

    @Autowired Wallets wallets;
    @Autowired BankOperations operations;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate transaction;
    @Autowired ObjectMapper json;
    @MockitoBean BankClient bank;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TOPIC = "wallet.events";

    private UUID postMoney(UUID user, String endpoint, String body, int expectedStatus) throws Exception {
        var response = mvc.perform(post("/api/v1/" + endpoint)
                        .with(jwt().jwt(token -> token.subject(user.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse();
        return UUID.fromString(json.readTree(response.getContentAsByteArray()).required("id").asText());
    }

    private UUID fundedUser() throws Exception {
        UUID user = UUID.randomUUID();
        wallets.open(user, user.toString().substring(0, 10), "Sender");
        UUID deposit = postMoney(user, "deposits", "{\"amount\":1000}", 202);
        operations.applyResult(new BankResult("test", deposit, "CHARGE", 1000, Status.SUCCEEDED, null));
        await().atMost(TIMEOUT).until(() -> sent(deposit) == 1);
        return user;
    }

    private UUID transfer(UUID user, UUID recipient) throws Exception {
        return postMoney(user, "transfers", "{\"toWalletId\":\"" + recipient + "\",\"amount\":10}", 201);
    }

    private UUID recipient() {
        UUID user = UUID.randomUUID();
        return wallets.open(user, user.toString().substring(0, 10), "Recipient").wallet().id();
    }

    private int sent(UUID id) {
        return db.sql("select count(*) from outbox where aggregate_id=? and sent_at is not null")
                .param(id).query(Integer.class).single();
    }

    @Test void acc2_15_transfersDuringBrokerOutageDrainAutomatically_EVT1_EVT3() throws Exception {
        var user = fundedUser();
        var recipient = recipient();
        var transfers = new HashSet<UUID>();
        var docker = broker.getDockerClient();
        docker.pauseContainerCmd(broker.getContainerId()).exec();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                for (int i = 0; i < 8; i++) transfers.add(transfer(user, recipient));
            });
            assertThat(transfers).hasSize(8);
            // Keep the broker unavailable beyond the producer's delivery timeout.
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6))
                    .until(() -> transfers.stream().allMatch(id -> sent(id) == 0));
        } finally {
            docker.unpauseContainerCmd(broker.getContainerId()).exec();
        }
        var records = consumeUntil(transfers, "transfer.completed");
        assertThat(records).extracting(Message::key).containsAll(transfers);
        await().atMost(TIMEOUT).until(() -> transfers.stream().allMatch(id -> sent(id) == 1));
    }

    @Test void rolledBackSettlementLeavesOnlyCommittedEvents_EVT1() throws Exception {
        var user = fundedUser();
        UUID withdrawal = postMoney(user, "withdrawals", "{\"amount\":17}", 202);
        transaction.executeWithoutResult(tx -> {
            operations.applyResult(new BankResult("test", withdrawal, "PAYOUT", 17, Status.FAILED, "DECLINED"));
            assertThat(db.sql("select count(*) from outbox where aggregate_id=? and envelope->>'type'='withdrawal.failed'")
                    .param(withdrawal).query(Integer.class).single()).isOne();
            tx.setRollbackOnly();
        });
        assertThat(operations.get(user, BankOperations.OperationKind.WITHDRAWAL, withdrawal).status()).isEqualTo(Status.PENDING);
        assertThat(wallets.details(user).reserved()).isEqualTo(17);
        operations.applyResult(new BankResult("test", withdrawal, "PAYOUT", 17, Status.SUCCEEDED, null));
        var messages = consumeUntil(Set.of(withdrawal), "withdrawal.succeeded");
        assertThat(messages).extracting(message -> message.envelope.required("type").asText())
                .containsExactly("withdrawal.requested", "withdrawal.succeeded");
        assertThat(db.sql("select count(*) from outbox where aggregate_id=?").param(withdrawal)
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test void acknowledgedSendRetriesWithStableIdBeforeSettlementAcrossRelays_EVT2() throws Exception {
        var user = fundedUser();
        var recipient = recipient();
        db.sql("create sequence mark_attempt").update();
        db.sql("""
                create function fail_first_mark() returns trigger language plpgsql as $$
                begin
                    if nextval('mark_attempt') = 1 then
                        perform pg_advisory_xact_lock(6015);
                        raise exception 'test failure after Kafka acknowledgement';
                    end if;
                    return new;
                end $$
                """).update();
        db.sql("""
                create trigger fail_requested_mark before update on outbox for each row
                when (new.envelope->>'type'='withdrawal.requested') execute function fail_first_mark()
                """).update();

        UUID withdrawal;
        try (var gate = dataSource.getConnection(); var lock = gate.createStatement()) {
            gate.setAutoCommit(false);
            lock.execute("select pg_advisory_xact_lock(6015)");
            withdrawal = postMoney(user, "withdrawals", "{\"amount\":100}", 202);
            await().atMost(TIMEOUT).until(() -> db.sql("select is_called from mark_attempt").query(Boolean.class).single());
            operations.applyResult(new BankResult("test", withdrawal, "PAYOUT", 100, Status.SUCCEEDED, null));
            UUID unrelated = transfer(user, recipient);
            // One relay holds the requested row after acknowledgement. The other can drain a later transfer.
            await().atMost(TIMEOUT).until(() -> sent(unrelated) == 1);
            assertThat(sent(withdrawal)).isZero();
            gate.commit();
        }
        var records = consumeUntil(Set.of(withdrawal), "withdrawal.succeeded");
        assertThat(records).extracting(record -> record.envelope.required("type").asText())
                .containsExactly("withdrawal.requested", "withdrawal.requested", "withdrawal.succeeded");
        assertThat(records).extracting(Message::partition).containsOnly(records.getFirst().partition);
        assertThat(records.get(1).envelope).isEqualTo(records.getFirst().envelope);
        assertThat(records.getLast().envelope.required("eventId")).isNotEqualTo(records.getFirst().envelope.required("eventId"));
        await().atMost(TIMEOUT).until(() -> sent(withdrawal) == 2);
        WalletInvariantAssertions.verify(db, 0);
    }

    private record Message(UUID key, int partition, JsonNode envelope) {}

    private List<Message> consumeUntil(Set<UUID> wanted, String finalType) {
        var records = new ArrayList<Message>();
        var completed = new HashSet<UUID>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers", broker.getBootstrapServers(), "enable.auto.commit", false,
                "default.api.timeout.ms", 10000), new StringDeserializer(), new StringDeserializer())) {
            var partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(info -> new TopicPartition(TOPIC, info.partition())).toList();
            assertThat(partitions).hasSizeGreaterThanOrEqualTo(3);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            await().pollInSameThread().atMost(TIMEOUT).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    var key = UUID.fromString(record.key());
                    if (!wanted.contains(key)) continue;
                    var event = json.readTree(record.value());
                    assertThat(event.required("aggregateId").asText()).isEqualTo(key.toString());
                    records.add(new Message(key, record.partition(), event));
                    if (finalType.equals(event.required("type").asText())) completed.add(key);
                }
                return completed.containsAll(wanted);
            });
        }
        return records;
    }
}
