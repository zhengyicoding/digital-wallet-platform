package com.acme.notification.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

@Component
public class WalletEventConsumer {
    private final ObjectMapper json;
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final KafkaTemplate<byte[], byte[]> kafka;
    private final NotificationCounters counters;

    public WalletEventConsumer(ObjectMapper json, JdbcClient db, TransactionTemplate tx,
                              KafkaTemplate<byte[], byte[]> kafka, NotificationCounters counters) {
        this.json = json; this.db = db; this.tx = tx; this.kafka = kafka; this.counters = counters;
    }

    @KafkaListener(id = "wallet-notifications", topics = "wallet.events", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<byte[], byte[]> record) throws Exception {
        WalletEvent event;
        try {
            event = WalletEvent.parse(json, record.value());
        } catch (IOException | RuntimeException ex) {
            // NTF-4: preserve original bytes, including non-UTF8 input and null keys/values.
            // Failure here propagates; the source offset remains eligible for retry.
            kafka.send(new ProducerRecord<>("wallet.events.dlt", record.key(), record.value()))
                    .get(15, TimeUnit.SECONDS);
            counters.deadLettered();
            return;
        }
        if (event == null) return;
        boolean inserted = Boolean.TRUE.equals(tx.execute(status -> {
            int added = db.sql("insert into processed_events(event_id) values (?) on conflict do nothing")
                    .param(event.eventId()).update();
            if (added == 0) return false;
            for (var recipient : event.recipients()) {
                db.sql("insert into notifications(id,event_id,user_id,type,text,reference_id) values (?,?,?,?,?,?)")
                        .params(newId(), event.eventId(), recipient.user(), event.type(), recipient.text(), event.reference())
                        .update();
            }
            return true;
        }));
        // NTF-2: tx.execute has committed before the counter or source offset advances.
        counters.processed(inserted);
    }

    private static UUID newId() {
        UUID random = UUID.randomUUID();
        return new UUID((System.currentTimeMillis() << 16) | 0x7000 | (random.getMostSignificantBits() & 0xfff),
                random.getLeastSignificantBits());
    }

    @Configuration
    static class KafkaConfig {
        @Bean
        org.springframework.kafka.config.ContainerCustomizer<Object, Object,
                org.springframework.kafka.listener.ConcurrentMessageListenerContainer<Object, Object>> shutdownDrain() {
            return container -> {
                // OPS-8: finish the current record and commit only after its database transaction.
                // Leave the rest of a fetched batch for redelivery after restart.
                container.getContainerProperties().setStopImmediate(true);
                container.getContainerProperties().setShutdownTimeout(35000);
            };
        }
        @Bean NewTopic walletEvents() { return TopicBuilder.name("wallet.events").partitions(3).replicas(1).build(); }
        @Bean NewTopic deadLetters() { return TopicBuilder.name("wallet.events.dlt").partitions(3).replicas(1).build(); }
        @Bean CommonErrorHandler retryFailures() {
            var handler = new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
            // No exhausted recovery path may acknowledge a database or Kafka outage.
            handler.setClassifications(java.util.Map.of(Exception.class, true), true);
            return handler;
        }
    }
}
