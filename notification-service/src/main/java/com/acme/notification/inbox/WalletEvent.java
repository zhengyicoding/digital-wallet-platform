package com.acme.notification.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

record WalletEvent(UUID eventId, String type, UUID reference, List<Recipient> recipients) {
    record Recipient(UUID user, String text) {}
    private static final Set<String> TYPES = Set.of("transfer.completed", "deposit.succeeded", "deposit.failed",
            "withdrawal.requested", "withdrawal.succeeded", "withdrawal.failed");

    // EVT-5: inspect type/version before interpreting the version-specific payload.
    static WalletEvent parse(ObjectMapper json, byte[] bytes) throws IOException {
        JsonNode e = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(bytes == null ? new byte[0] : bytes);
        if (e == null || !e.isObject()) throw new IllegalArgumentException("Expected event object");
        String type = text(e, "type");
        JsonNode version = e.required("version");
        if (!version.isIntegralNumber()) throw new IllegalArgumentException("Expected version");
        if (!TYPES.contains(type) || !version.canConvertToInt() || version.intValue() != 1) return null;
        UUID id = uuid(e, "eventId"), reference = uuid(e, "aggregateId");
        Instant.parse(text(e, "occurredAt"));
        JsonNode p = e.required("payload");
        JsonNode amount = p.required("amount");
        if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() <= 0
                || !"USD".equals(text(p, "currency"))) throw new IllegalArgumentException("Invalid money");
        long cents = amount.longValue();
        String money = String.format(Locale.US, "$%,d.%02d", cents / 100, cents % 100);
        if (type.equals("transfer.completed")) {
            if (!reference.equals(uuid(p, "transferId"))) throw new IllegalArgumentException("Invalid reference");
            JsonNode from = p.required("from"), to = p.required("to");
            uuid(from, "walletId"); uuid(to, "walletId");
            return new WalletEvent(id, type, reference, List.of(
                    new Recipient(uuid(from, "userId"), "You sent " + money + " to " + text(to, "displayName")),
                    new Recipient(uuid(to, "userId"), "You received " + money + " from " + text(from, "displayName"))));
        }
        if (!reference.equals(uuid(p, type.startsWith("deposit.") ? "depositId" : "withdrawalId")))
            throw new IllegalArgumentException("Invalid reference");
        uuid(p, "walletId");
        String message = switch (type) {
            case "deposit.succeeded" -> "Your deposit of " + money + " is complete";
            case "deposit.failed" -> "Your deposit of " + money + " failed";
            case "withdrawal.requested" -> "Your withdrawal of " + money + " is in progress";
            case "withdrawal.succeeded" -> "Your withdrawal of " + money + " is complete";
            case "withdrawal.failed" -> "Your withdrawal of " + money + " failed and the money is back in your wallet";
            default -> throw new IllegalArgumentException("Unknown event");
        };
        return new WalletEvent(id, type, reference, List.of(new Recipient(uuid(p, "userId"), message)));
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual()) throw new IllegalArgumentException("Expected text");
        return value.textValue();
    }
    private static UUID uuid(JsonNode node, String field) { return UUID.fromString(text(node, field)); }
}
