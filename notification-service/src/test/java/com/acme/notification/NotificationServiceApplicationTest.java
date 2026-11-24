package com.acme.notification;

import com.acme.notification.inbox.NotificationCounters;
import com.acme.notification.inbox.Notifications;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(tracing = true)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationServiceApplicationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaContainer broker = new KafkaContainer("apache/kafka:3.8.0")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    static com.nimbusds.jose.jwk.RSAKey signingKey;
    static com.sun.net.httpserver.HttpServer jwks;
    @org.junit.jupiter.api.AfterAll static void closeJwks() { if (jwks != null) jwks.stop(0); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        signingKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("m11").generate();
        jwks = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", exchange -> {
            byte[] body = new com.nimbusds.jose.jwk.JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        jwks.start();
        r.add("notification.jwks-uri", () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks");
        r.add("spring.kafka.bootstrap-servers", broker::getBootstrapServers);
    }
    @Autowired KafkaTemplate<byte[], byte[]> kafka;
    // Boot declares KafkaTemplate<Object,Object>; select the spy without narrowing its generic bean type.
    @SuppressWarnings("rawtypes")
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(name = "kafkaTemplate")
    KafkaTemplate kafkaSpy;
    @Autowired JdbcClient db;
    @Autowired Notifications notifications;
    @Autowired NotificationCounters counters;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    static final Duration TIMEOUT = Duration.ofSeconds(40);
    static final String API = "/api/v1/notifications";

    private byte[] event(UUID eventId, String type, UUID from, UUID to) throws Exception {
        UUID reference = UUID.randomUUID();
        Map<String,Object> payload = new HashMap<>();
        payload.put("amount", 125000L); payload.put("currency", "USD"); payload.put("unknown", true);
        if (type.equals("transfer.completed")) {
            payload.put("transferId", reference); payload.put("note", "Lunch");
            payload.put("from", Map.of("walletId", UUID.randomUUID(), "userId", from, "displayName", "Alice Chen"));
            payload.put("to", Map.of("walletId", UUID.randomUUID(), "userId", to, "displayName", "Bob Lee"));
        } else {
            payload.put(type.startsWith("deposit.") ? "depositId" : "withdrawalId", reference);
            payload.put("walletId", UUID.randomUUID()); payload.put("userId", from); payload.put("failureReason", "DECLINED");
        }
        return json.writeValueAsBytes(Map.of("eventId", eventId, "type", type, "version", 1,
                "occurredAt", Instant.now().toString(), "aggregateId", reference, "payload", payload, "futureField", 42));
    }
    private void send(byte[] key, byte[] value) throws Exception {
        kafka.send(new ProducerRecord<>("wallet.events", 0, key, value)).get();
    }
    private void transfer(UUID id, UUID from, UUID to) throws Exception { send(null, event(id, "transfer.completed", from, to)); }
    private void processed(UUID id) {
        await().atMost(TIMEOUT).until(() -> db.sql("select count(*) from processed_events where event_id=?")
                .param(id).query(Integer.class).single() == 1);
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
        return signed(id, "USER");
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor signed(UUID id, String role) {
        try {
            var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("digital-wallet-auth")
                    .subject(id.toString()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("roles", List.of(role)).build();
            var token = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
                    com.nimbusds.jose.JWSAlgorithm.RS256).keyID("m11").build(), claims);
            token.sign(new com.nimbusds.jose.crypto.RSASSASigner(signingKey));
            return request -> { request.addHeader("Authorization", "Bearer " + token.serialize()); return request; };
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    @Test void acc2_16_duplicatesAcrossRestart_NTF2_EVT4() throws Exception {
        UUID id = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        var before = counters.stats();
        byte[] event = event(id, "transfer.completed", a, b);
        var original = kafka.send(new ProducerRecord<byte[], byte[]>("wallet.events", 0, null, event)).get().getRecordMetadata();
        send(null, event);
        await().atMost(TIMEOUT).until(() -> counters.stats().duplicatesIgnored() == before.duplicatesIgnored() + 1);
        var container = listeners.getListenerContainer("wallet-notifications");
        var stopped = new java.util.concurrent.CountDownLatch(1);
        container.stop(stopped::countDown);
        assertThat(stopped.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // Rewind the committed offset while stopped, then replay the original records after rejoining the group.
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
            admin.alterConsumerGroupOffsets("notification-service", Map.of(new TopicPartition("wallet.events", 0),
                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(original.offset()))).all().get();
        }
        container.start();
        await().atMost(TIMEOUT).until(() -> counters.stats().duplicatesIgnored() == before.duplicatesIgnored() + 3);
        assertThat(notifications.list(a, false, 20, null).items()).singleElement()
                .extracting(Notifications.Notification::text).isEqualTo("You sent $1,250.00 to Bob Lee");
        assertThat(notifications.list(b, false, 20, null).items()).singleElement()
                .extracting(Notifications.Notification::text).isEqualTo("You received $1,250.00 from Alice Chen");
        assertThat(counters.stats().eventsProcessed()).isEqualTo(before.eventsProcessed() + 1);
    }

    @Test void acc2_17_rawDltThenValidOnSamePartition_NTF4() throws Exception {
        byte[] key = {0, (byte) 255, 7}, bad = {(byte) 255, '{', 0};
        UUID id = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        long dead = counters.stats().deadLettered();
        try (var consumer = new KafkaConsumer<byte[],byte[]>(Map.of("bootstrap.servers", broker.getBootstrapServers(),
                "enable.auto.commit", false), new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            var partitions = List.of(new TopicPartition("wallet.events.dlt", 0), new TopicPartition("wallet.events.dlt", 1),
                    new TopicPartition("wallet.events.dlt", 2));
            consumer.assign(partitions); consumer.seekToBeginning(partitions);
            // A failed Kafka send must not acknowledge the source or skip to the valid record.
            org.mockito.Mockito.doReturn(java.util.concurrent.CompletableFuture.failedFuture(
                    new org.apache.kafka.common.KafkaException("injected transient DLT failure")))
                    .doCallRealMethod().when(kafka).send(org.mockito.ArgumentMatchers.<ProducerRecord<byte[], byte[]>>argThat(
                            r -> r != null && r.topic().equals("wallet.events.dlt") && Arrays.equals(r.key(), key)));
            send(key, bad); transfer(id, a, b);
            processed(id);
            await().pollInSameThread().atMost(TIMEOUT).until(() -> {
                for (var r : consumer.poll(Duration.ofMillis(200))) {
                    if (Arrays.equals(r.key(), key)) { assertThat(r.value()).isEqualTo(bad); return true; }
                }
                return false;
            });
        }
        assertThat(notifications.unread(a)).isOne(); assertThat(notifications.unread(b)).isOne();
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2)).send(
                org.mockito.ArgumentMatchers.<ProducerRecord<byte[], byte[]>>argThat(
                        r -> r != null && r.topic().equals("wallet.events.dlt") && Arrays.equals(r.key(), key)));
        assertThat(counters.stats().deadLettered()).isEqualTo(dead + 1);
    }

    @Test void unsupportedEventsIgnoredAndAllSixTexts_NTF1_EVT5() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var before = counters.stats();
        var unknown = json.createObjectNode().put("type", "future.event").put("version", 1);
        send(null, json.writeValueAsBytes(unknown));
        send(null, json.writeValueAsBytes(unknown.put("type", "transfer.completed").put("version", 2)));
        List<String> types = List.of("deposit.succeeded", "deposit.failed", "withdrawal.requested", "withdrawal.succeeded", "withdrawal.failed");
        for (String type : types) send(null, event(UUID.randomUUID(), type, a, b));
        UUID last = UUID.randomUUID(); transfer(last, a, b); processed(last);
        assertThat(notifications.list(a, false, 20, null).items()).extracting(Notifications.Notification::text)
                .containsExactlyInAnyOrder("Your deposit of $1,250.00 is complete", "Your deposit of $1,250.00 failed",
                        "Your withdrawal of $1,250.00 is in progress", "Your withdrawal of $1,250.00 is complete",
                        "Your withdrawal of $1,250.00 failed and the money is back in your wallet", "You sent $1,250.00 to Bob Lee");
        await().atMost(TIMEOUT).until(() -> counters.stats().eventsProcessed() == before.eventsProcessed() + 6);
        assertThat(counters.stats().deadLettered()).isEqualTo(before.deadLettered());
        assertThat(counters.stats().duplicatesIgnored()).isEqualTo(before.duplicatesIgnored());
    }

    @Test void customerApiPaginationReadAndCrossUser_ACC2_7_NTF3_API5() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int i = 0; i < 3; i++) { UUID id = UUID.randomUUID(); transfer(id, a, b); processed(id); }
        var first = json.readTree(mvc.perform(get(API).with(user(a)).param("limit", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String cursor = first.get("nextCursor").asText(), id = first.get("items").get(0).get("id").asText();
        UUID added = UUID.randomUUID(); transfer(added, a, b); processed(added);
        var second = json.readTree(mvc.perform(get(API).with(user(a)).param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(second.get("items").size()).isOne(); assertThat(second.get("nextCursor").isNull()).isTrue();
        var ids = new HashSet<String>(); first.get("items").forEach(n -> ids.add(n.get("id").asText()));
        second.get("items").forEach(n -> assertThat(ids.add(n.get("id").asText())).isTrue());
        mvc.perform(post(API + "/" + id + "/read").with(user(b))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // ACC-2 #7: a valid other-user token cannot change the owner's unread notification.
        assertThat(notifications.unread(a)).isEqualTo(4);
        assertThat(db.sql("select read from notifications where id=?").param(UUID.fromString(id))
                .query(Boolean.class).single()).isFalse();
        for (int i = 0; i < 2; i++) mvc.perform(post(API + "/" + id + "/read").with(user(a))).andExpect(status().isNoContent());
        mvc.perform(get(API + "/unread-count").with(user(a))).andExpect(jsonPath("$.count").value(3));
        mvc.perform(get(API).with(user(a)).param("unreadOnly", "true")).andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(post(API + "/read-all").with(user(a))).andExpect(status().isNoContent());
        mvc.perform(get(API + "/unread-count").with(user(a))).andExpect(jsonPath("$.count").value(0));
        assertThat(notifications.unread(b)).isEqualTo(4);
        for (String limit : List.of("0", "101")) mvc.perform(get(API).with(user(a)).param("limit", limit))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(API).with(user(a)).param("cursor", "bad")).andExpect(status().isBadRequest());
    }

    @Test void adminStatsAndPublicDocs_NTF5_SEC1_SEC3() throws Exception {
        mvc.perform(get(API)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get(API + "/admin/stats").with(user(UUID.randomUUID()))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        var stats = counters.stats();
        mvc.perform(get(API + "/admin/stats").with(signed(UUID.randomUUID(), "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eventsProcessed").value(stats.eventsProcessed()))
                .andExpect(jsonPath("$.duplicatesIgnored").value(stats.duplicatesIgnored()))
                .andExpect(jsonPath("$.deadLettered").value(stats.deadLettered()));
        mvc.perform(get("/docs/notifications/")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/docs/notifications/v3/api-docs")).andExpect(status().isOk());
    }

    @Test void malformedSupportedEnvelopeGoesToDltAndContinues_NTF4() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var bad = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(event(UUID.randomUUID(), "transfer.completed", a, b));
        bad.put("occurredAt", "invalid timestamp");
        long before = counters.stats().deadLettered();
        send(null, json.writeValueAsBytes(bad));
        send(null, "{} trailing".getBytes(StandardCharsets.UTF_8));
        send(null, null);
        UUID valid = UUID.randomUUID(); transfer(valid, a, b); processed(valid);
        assertThat(counters.stats().deadLettered()).isEqualTo(before + 3);
        assertThat(notifications.unread(a)).isOne();
    }

    @Test void partialInsertRollsBackAndRetries_NTF2_EVT4() throws Exception {
        UUID id = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        db.sql("create sequence notification_failure_attempt").update();
        db.sql("""
                create function notification_fail_once() returns trigger language plpgsql as $$
                begin
                    if new.event_id = '%s'::uuid and nextval('notification_failure_attempt') = 2 then
                        raise exception 'transient test failure on second recipient';
                    end if;
                    return new;
                end $$
                """.formatted(id)).update();
        db.sql("create trigger fail_once before insert on notifications for each row execute function notification_fail_once()").update();
        try {
            transfer(id, a, b); processed(id);
            assertThat(notifications.unread(a)).isOne(); assertThat(notifications.unread(b)).isOne();
            assertThat(db.sql("select last_value from notification_failure_attempt").query(Long.class).single()).isEqualTo(4);
        } finally {
            db.sql("drop trigger fail_once on notifications").update();
            db.sql("drop function notification_fail_once()").update();
        }
    }
    @Test void ops5_ntf5_prometheusReusesNotificationCounters() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        var metrics = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String name : List.of("notification_events_processed_total", "notification_duplicates_ignored_total", "notification_dead_lettered_total")) {
            assertThat(metrics).contains(name + "{service=\"notification-service\"} ");
        }
        assertThat(metrics).contains("http_server_requests_seconds_bucket");
    }

}
