package com.acme.wallet.outbox;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "wallet.outbox.enabled", matchIfMissing = true)
public class OutboxRelay {
    private static final String TOPIC = "wallet.events";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcClient db;
    private final TransactionTemplate transaction;
    private final KafkaAdmin admin;
    private final KafkaTemplate<String, String> kafka;
    private boolean topicReady;

    public OutboxRelay(JdbcClient db, TransactionTemplate transaction, KafkaAdmin admin,
                       KafkaTemplate<String, String> kafka) {
        this.db = db;
        this.transaction = transaction;
        this.admin = admin;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.poll-ms:250}")
    public void publishPending() {
        try {
            if (!topicReady) {
                admin.createOrModifyTopics(TopicBuilder.name(TOPIC).partitions(3).build());
                topicReady = true;
            }
            for (int count = 0; count < 100; count++) {
                if (!transaction.execute(status -> publishNext())) break;
            }
        } catch (RuntimeException failure) {
            topicReady = false;
            log.warn("Outbox publication will retry on the next poll", failure);
        }
    }

    private record Pending(long sequenceId, UUID aggregateId, String envelope) {}

    private boolean publishNext() {
        // A locked or retrying predecessor remains visible as unsent to every other relay.
        var next = db.sql("""
                select o.sequence_id, o.aggregate_id, o.envelope::text from outbox o
                where o.sent_at is null and not exists (
                    select 1 from outbox earlier where earlier.aggregate_id=o.aggregate_id
                    and earlier.sequence_id<o.sequence_id and earlier.sent_at is null
                )
                order by o.sequence_id limit 1 for update of o skip locked
                """).query(Pending.class).optional();
        if (next.isEmpty()) return false;

        var row = next.get();
        // Keep only the outbox row locked until the producer finishes (bounded by its delivery timeout).
        kafka.send(TOPIC, row.aggregateId.toString(), row.envelope).join();
        db.sql("update outbox set sent_at=clock_timestamp() where sequence_id=?").param(row.sequenceId).update();
        return true;
    }
}
