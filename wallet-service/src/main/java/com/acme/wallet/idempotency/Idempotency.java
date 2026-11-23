package com.acme.wallet.idempotency;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.acme.wallet.api.WalletProblems;
import com.acme.wallet.api.WalletProblems.Failure;
import com.acme.wallet.api.WalletProblems.Kind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Idempotency {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final ObjectWriter writer;
    private final TransactionTemplate transaction;

    public Idempotency(JdbcClient db, ObjectMapper json, PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.writer = json.writer().with(JsonNodeFeature.WRITE_PROPERTIES_SORTED);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Resource(UUID id, Object body) {}

    private record Snapshot(int status, String body, String location) {
        ResponseEntity<byte[]> response(boolean replayed) {
            var response = ResponseEntity.status(status).location(URI.create(location)).contentType(MediaType.APPLICATION_JSON);
            if (replayed) response.header("Idempotent-Replayed", "true");
            return response.body(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    public <T> ResponseEntity<byte[]> execute(UUID userId, String endpoint, String key, JsonNode body,
            Class<T> requestType, HttpStatus successStatus, Function<T, Resource> create) {
        if (key == null || key.isEmpty() || key.codePointCount(0, key.length()) > 64) {
            throw new Failure(Kind.IDEMPOTENCY_KEY_REQUIRED);
        }
        String requestBody = serialize(body);
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.execute(tx -> replayOrCreate(userId, endpoint, key, requestBody, successStatus,
                        () -> create.apply(readRequest(body, requestType))));
            } catch (RuntimeException failure) {
                if (attempt >= 3 || !retryable(failure)) throw failure;
            }
        }
    }

    private ResponseEntity<byte[]> replayOrCreate(UUID userId, String endpoint, String key, String requestBody,
            HttpStatus successStatus, Supplier<Resource> create) {
        // PostgreSQL releases this lock on either commit or rollback (IDM-4/6).
        boolean acquired = db.sql("select pg_try_advisory_xact_lock(hashtextextended(?, 0))")
                .param(userId + ":" + endpoint + ":" + key).query(Boolean.class).single();
        // A fresh READ COMMITTED statement sees any response committed before the lock attempt.
        var stored = find(userId, endpoint, key, requestBody);
        if (stored.isPresent()) return stored.get().response(true);
        if (!acquired) throw new Failure(Kind.REQUEST_IN_PROGRESS);

        var resource = create.get();
        var snapshot = new Snapshot(successStatus.value(), serialize(resource.body), endpoint + "/" + resource.id);
        db.sql("""
                insert into idempotency_records
                    (user_id, endpoint, request_key, request_body, response_status, response_body, location)
                values (?,?,?,?,?,?,?)
                """).params(userId, endpoint, key, requestBody, snapshot.status, snapshot.body, snapshot.location).update();
        return snapshot.response(false);
    }

    private Optional<Snapshot> find(UUID userId, String endpoint, String key, String requestBody) {
        return db.sql("""
                select request_body, response_status, response_body, location from idempotency_records
                where user_id=? and endpoint=? and request_key=?
                """).params(userId, endpoint, key).query((rs, n) -> {
                    if (!requestBody.equals(rs.getString("request_body"))) throw new Failure(Kind.IDEMPOTENCY_KEY_REUSED);
                    return new Snapshot(rs.getInt("response_status"), rs.getString("response_body"), rs.getString("location"));
                }).optional();
    }

    private <T> T readRequest(JsonNode body, Class<T> type) {
        try {
            if (body.isNull()) throw WalletProblems.invalid("body", "Use a valid JSON object");
            return json.treeToValue(body, type);
        } catch (JsonProcessingException ex) {
            throw WalletProblems.invalid("body", "Use a valid JSON object");
        }
    }

    private String serialize(Object value) {
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize idempotency record", ex);
        }
    }

    private static boolean retryable(Throwable cause) {
        for (; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql &&
                    ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) return true;
        }
        return false;
    }
}
