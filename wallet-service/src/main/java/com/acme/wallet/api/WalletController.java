package com.acme.wallet.api;

import java.util.UUID;

import com.acme.wallet.ledger.Statements;
import com.acme.wallet.ledger.Wallets;
import com.acme.wallet.transfer.Transfers;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1")
class WalletController {
    private final Wallets wallets;
    private final Statements statements;
    private final Transfers transfers;

    WalletController(Wallets wallets, Statements statements, Transfers transfers) {
        this.wallets = wallets;
        this.statements = statements;
        this.transfers = transfers;
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @ApiResponse(responseCode = "200", description = "Existing wallet", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "201", description = "Wallet opened", useReturnTypeSchema = true)
    @PostMapping("/wallets")
    ResponseEntity<Wallets.Wallet> open(@AuthenticationPrincipal Jwt jwt) {
        var result = wallets.open(caller(jwt), jwt.getClaimAsString("username"), jwt.getClaimAsString("name"));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.wallet());
    }

    @GetMapping("/wallets/me")
    Wallets.Wallet me(@AuthenticationPrincipal Jwt jwt) {
        return wallets.details(caller(jwt));
    }

    @GetMapping("/wallets/lookup")
    Wallets.Recipient lookup(@RequestParam String username) {
        if (!username.matches("[a-z0-9_]{3,20}")) throw WalletProblems.invalid("username", "Invalid username");
        return wallets.lookup(username);
    }

    @GetMapping("/wallets/me/entries")
    Statements.Page entries(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Statements.EntryType type,
                            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) String cursor) {
        return statements.list(caller(jwt), type, limit, cursor);
    }

    @ApiResponse(responseCode = "201", description = "Transfer completed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Transfers.Transfer.class)))
    @PostMapping("/transfers")
    ResponseEntity<byte[]> send(@AuthenticationPrincipal Jwt jwt,
            @Parameter(required = true, schema = @Schema(minLength = 1, maxLength = 64))
            @RequestHeader(name="Idempotency-Key", required=false) String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = Transfers.Request.class)))
            @RequestBody JsonNode request) {
        return transfers.send(caller(jwt), key, request);
    }

    @GetMapping("/transfers/{id}")
    Transfers.Transfer transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return transfers.get(caller(jwt), id);
    }
}
