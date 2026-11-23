package com.acme.wallet.bank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.acme.wallet.api.WalletProblems;
import com.acme.wallet.bank.BankOperations.BankResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
class BankWebhook {
    private final SecretKeySpec key;
    private final ObjectMapper json;
    private final BankOperations operations;

    BankWebhook(@Value("${bank.webhook-secret}") String secret, ObjectMapper json, BankOperations operations) {
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.json = json;
        this.operations = operations;
    }

    @PostMapping("/internal/webhooks/bank")
    void receive(@RequestHeader(name="X-Bank-Timestamp", required=false) String timestamp,
                 @RequestHeader(name="X-Bank-Signature", required=false) String signature,
                 @RequestBody byte[] body) throws GeneralSecurityException {
        if (!authenticated(timestamp, signature, body)) {
            throw new WalletProblems.Failure(WalletProblems.Kind.INVALID_SIGNATURE);
        }
        try {
            operations.applyResult(json.readValue(body, BankResult.class));
        } catch (IOException malformed) {
            throw WalletProblems.invalid("body", "Use a valid bank transaction");
        }
    }

    private boolean authenticated(String timestamp, String signature, byte[] body) throws GeneralSecurityException {
        if (timestamp == null || !timestamp.matches("[0-9]{1,18}") ||
                signature == null || !signature.matches("[0-9a-f]{64}")) return false;
        long seconds = Long.parseLong(timestamp);
        long now = Instant.now().getEpochSecond();
        if (seconds < now - 300 || seconds > now) return false;
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature));
    }
}
