package com.acme.wallet.observability;

import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** OPS-4/5: only committed transitions count; replays, duplicates and rollbacks do not. */
@Component
public class OperationTelemetry {
    public enum Type { TRANSFER, DEPOSIT, WITHDRAWAL }
    public enum Result { COMPLETED, PENDING, SUCCEEDED, FAILED }
    public record Outcome(UUID operationId, UUID userId, Type type, Result result) {}
    private static final Logger log = LoggerFactory.getLogger(OperationTelemetry.class);
    private final MeterRegistry registry;
    public OperationTelemetry(MeterRegistry registry) {
        this.registry = registry;
        for (Type type : Type.values()) {
            for (Result result : Result.values()) {
                if ((type == Type.TRANSFER) == (result == Result.COMPLETED))
                    registry.counter("wallet.operations", "type", type.name(), "result", result.name());
            }
        }
    }
    @TransactionalEventListener
    public void committed(Outcome outcome) {
        registry.counter("wallet.operations", "type", outcome.type.name(), "result", outcome.result.name()).increment();
        String previousUser = MDC.get("userId");
        MDC.put("userId", outcome.userId.toString());
        try {
            log.atInfo().addKeyValue("event", "wallet.operation")
                .addKeyValue("operationId", outcome.operationId.toString())
                .addKeyValue("type", outcome.type.name()).addKeyValue("outcome", outcome.result.name())
                .log("Wallet operation committed");
        } finally {
            if (previousUser == null) MDC.remove("userId");
            else MDC.put("userId", previousUser);
        }
    }
}
