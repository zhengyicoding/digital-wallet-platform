package com.acme.wallet.outbox;

import java.time.Instant;
import java.util.UUID;

import com.acme.wallet.ledger.Wallets;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Outbox {
    private final JdbcClient db;
    private final ObjectMapper json;

    public Outbox(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    private record Envelope(UUID eventId, String type, int version, Instant occurredAt, UUID aggregateId, Object payload) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String type, UUID aggregateId, Instant occurredAt, Object payload) {
        UUID eventId = Wallets.newId();
        try {
            String envelope = json.writeValueAsString(new Envelope(eventId, type, 1, occurredAt, aggregateId, payload));
            db.sql("insert into outbox(event_id,aggregate_id,envelope) values (?,?,cast(? as jsonb))")
                    .params(eventId, aggregateId, envelope).update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize wallet event", ex);
        }
    }
}
