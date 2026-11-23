package com.acme.wallet.api;

import java.util.UUID;
import com.acme.wallet.bank.BankOperations;
import com.acme.wallet.bank.BankOperations.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearer")
class BankOperationController {
    private final BankOperations operations;

    BankOperationController(BankOperations operations) {
        this.operations = operations;
    }

    @ApiResponse(responseCode = "202", description = "Pending deposit", content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Operation.class)))
    @PostMapping("/deposits")
    ResponseEntity<byte[]> createDeposit(@AuthenticationPrincipal Jwt jwt,
            @Parameter(required = true) @RequestHeader(name="Idempotency-Key", required=false) String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = Request.class)))
            @RequestBody JsonNode body) {
        return operations.create(UUID.fromString(jwt.getSubject()), OperationKind.DEPOSIT, key, body);
    }

    @GetMapping("/deposits/{id}")
    Operation deposit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return operations.get(UUID.fromString(jwt.getSubject()), OperationKind.DEPOSIT, id);
    }

    @GetMapping("/deposits")
    Page deposits(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Status status,
            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) String cursor) {
        return operations.list(UUID.fromString(jwt.getSubject()), OperationKind.DEPOSIT, status, limit, cursor);
    }

    @ApiResponse(responseCode = "202", description = "Pending withdrawal", content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Operation.class)))
    @PostMapping("/withdrawals")
    ResponseEntity<byte[]> createWithdrawal(@AuthenticationPrincipal Jwt jwt,
            @Parameter(required = true) @RequestHeader(name="Idempotency-Key", required=false) String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = Request.class)))
            @RequestBody JsonNode body) {
        return operations.create(UUID.fromString(jwt.getSubject()), OperationKind.WITHDRAWAL, key, body);
    }

    @GetMapping("/withdrawals/{id}")
    Operation withdrawal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return operations.get(UUID.fromString(jwt.getSubject()), OperationKind.WITHDRAWAL, id);
    }

    @GetMapping("/withdrawals")
    Page withdrawals(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Status status,
            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) String cursor) {
        return operations.list(UUID.fromString(jwt.getSubject()), OperationKind.WITHDRAWAL, status, limit, cursor);
    }
}
