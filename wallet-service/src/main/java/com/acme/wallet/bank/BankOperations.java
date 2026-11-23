package com.acme.wallet.bank;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.acme.wallet.api.WalletProblems;
import com.acme.wallet.api.WalletProblems.Failure;
import com.acme.wallet.api.WalletProblems.Kind;
import com.acme.wallet.idempotency.Idempotency;
import com.acme.wallet.ledger.Statements.Cursor;
import com.acme.wallet.ledger.Wallets;
import com.acme.wallet.outbox.Outbox;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankOperations {
    public enum OperationKind {
        DEPOSIT("/api/v1/deposits", "CHARGE"), WITHDRAWAL("/api/v1/withdrawals", "PAYOUT");

        final String endpoint;
        final String bankType;

        OperationKind(String endpoint, String bankType) {
            this.endpoint = endpoint;
            this.bankType = bankType;
        }
    }

    public enum Status { PENDING, SUCCEEDED, FAILED }
    public record Request(Long amount) {}
    public record Operation(UUID id, long amount, String currency, Status status, String failureReason,
                            Instant createdAt, Instant completedAt) {}
    public record Page(List<Operation> items, String nextCursor) {}
    public record Submission(UUID reference, String type, long amount) {}
    public record BankResult(String bankTransactionId, UUID reference, String type, long amount,
                             Status status, String reason) {}

    private record WithdrawalRequested(UUID withdrawalId, UUID walletId, UUID userId, long amount, String currency) {}
    private record WithdrawalSettled(UUID withdrawalId, UUID walletId, UUID userId, long amount, String currency,
                                     String failureReason) {}
    private record DepositSettled(UUID depositId, UUID walletId, UUID userId, long amount, String currency,
                                  String failureReason) {}

    private final Outbox outbox;
    private final JdbcClient db;
    private final Wallets wallets;
    private final Idempotency idempotency;
    private final ApplicationEventPublisher events;

    public BankOperations(JdbcClient db, Wallets wallets, Idempotency idempotency, ApplicationEventPublisher events,
                          Outbox outbox) {
        this.outbox = outbox;
        this.db = db;
        this.wallets = wallets;
        this.idempotency = idempotency;
        this.events = events;
    }

    public ResponseEntity<byte[]> create(UUID user, OperationKind kind, String key, JsonNode body) {
        return idempotency.execute(user, kind.endpoint, key, body, Request.class, HttpStatus.ACCEPTED, request -> {
            long amount = wallets.amount(request.amount);
            UUID wallet = wallets.id(user);
            lockWallet(wallet);
            Timestamp now = db.sql("select clock_timestamp()").query(Timestamp.class).single();
            if (kind == OperationKind.WITHDRAWAL) wallets.checkSpending(wallet, amount, now);
            UUID id = Wallets.newId();
            db.sql("insert into bank_operations(id,wallet_id,kind,amount,created_at) values (?,?,?,?,?)")
                    .params(id, wallet, kind.name(), amount, now).update();
            if (kind == OperationKind.WITHDRAWAL) {
                post(id, kind, wallet, systemAccount("PAYOUT_HOLD"), amount, "WITHDRAWAL");
                outbox.append("withdrawal.requested", id, now.toInstant(),
                        new WithdrawalRequested(id, wallet, user, amount, "USD"));
            }
            events.publishEvent(new Submission(id, kind.bankType, amount));
            return new Idempotency.Resource(id, new Operation(id, amount, "USD", Status.PENDING, null, now.toInstant(), null));
        });
    }

    private UUID lockWallet(UUID wallet) {
        return db.sql("select user_id from wallets where id=? for update").param(wallet).query(UUID.class).single();
    }

    private UUID systemAccount(String name) {
        return db.sql("select id from accounts where system_name=?").param(name).query(UUID.class).single();
    }

    private void post(UUID reference, OperationKind kind, UUID debit, UUID credit, long amount, String entryType) {
        UUID posting = Wallets.newId();
        db.sql("insert into postings(id,reference_type,reference_id) values (?,?,?)")
                .params(posting, kind.name(), reference).update();
        db.sql("""
                insert into ledger_entries(id,posting_id,account_id,type,amount) values
                (?,?,?,?,?), (?,?,?,?,?)
                """).params(Wallets.newId(), posting, debit, entryType, -amount,
                            Wallets.newId(), posting, credit, entryType, amount).update();
    }

    private record Locked(UUID wallet, OperationKind kind, long amount, Status status) {}

    @Transactional
    public void applyResult(BankResult result) {
        if (result == null || result.reference == null || result.status == null) {
            throw WalletProblems.invalid("body", "Supply a bank transaction with a reference and status");
        }
        var row = db.sql("select * from bank_operations where id=? for update").param(result.reference)
                .query((rs, n) -> new Locked(rs.getObject("wallet_id", UUID.class),
                        OperationKind.valueOf(rs.getString("kind")), rs.getLong("amount"),
                        Status.valueOf(rs.getString("status")))).optional().orElseThrow(() -> new Failure(Kind.NOT_FOUND));
        if (row.status != Status.PENDING) return;
        if (!row.kind.bankType.equals(result.type) || row.amount != result.amount) {
            throw WalletProblems.invalid("body", "Bank transaction does not match the operation");
        }
        if (result.status == Status.PENDING) return;
        UUID user = lockWallet(row.wallet);
        boolean succeeded = result.status == Status.SUCCEEDED;
        if (row.kind == OperationKind.WITHDRAWAL) {
            post(result.reference, row.kind, systemAccount("PAYOUT_HOLD"),
                    succeeded ? systemAccount("EXTERNAL_BANK") : row.wallet, row.amount,
                    succeeded ? "WITHDRAWAL" : "WITHDRAWAL_REVERSAL");
        } else if (succeeded) {
            post(result.reference, row.kind, systemAccount("EXTERNAL_BANK"), row.wallet, row.amount, "DEPOSIT");
        }
        String reason = succeeded ? null : result.reason;
        Instant completedAt = db.sql("""
                update bank_operations set status=?,failure_reason=?,completed_at=clock_timestamp() where id=?
                returning completed_at
                """).params(result.status.name(), reason, result.reference).query(Timestamp.class).single().toInstant();
        if (row.kind == OperationKind.WITHDRAWAL) {
            outbox.append(succeeded ? "withdrawal.succeeded" : "withdrawal.failed", result.reference, completedAt,
                    new WithdrawalSettled(result.reference, row.wallet, user, row.amount, "USD", reason));
        } else {
            outbox.append(succeeded ? "deposit.succeeded" : "deposit.failed", result.reference, completedAt,
                    new DepositSettled(result.reference, row.wallet, user, row.amount, "USD", reason));
        }
    }

    private static Operation read(ResultSet rs, int index) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new Operation(rs.getObject("id", UUID.class), rs.getLong("amount"), "USD",
                Status.valueOf(rs.getString("status")), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), completed == null ? null : completed.toInstant());
    }

    private static final String OWNED = """
            select o.* from bank_operations o join wallets w on o.wallet_id=w.id
            where w.user_id=:user and o.kind=:kind
            """;

    public Operation get(UUID user, OperationKind kind, UUID id) {
        return db.sql(OWNED + " and o.id=:id").param("user", user).param("kind", kind.name()).param("id", id)
                .query(BankOperations::read).optional().orElseThrow(() -> new Failure(Kind.NOT_FOUND));
    }

    public Page list(UUID user, OperationKind kind, Status status, int limit, String token) {
        if (limit < 1 || limit > 100) throw WalletProblems.invalid("limit", "Use a limit from 1 to 100");
        Cursor cursor = token == null ? null : Cursor.decode(token);
        var query = db.sql(OWNED + (cursor == null ? "" : " and (o.created_at,o.id)<(:time,:id)")
                + (status == null ? "" : " and o.status=:status") + " order by o.created_at desc,o.id desc limit :count")
                .param("user", user).param("kind", kind.name()).param("count", limit + 1);
        if (cursor != null) query = query.param("time", Timestamp.from(cursor.time())).param("id", cursor.id());
        if (status != null) query = query.param("status", status.name());
        var rows = query.query(BankOperations::read).list();
        if (rows.size() <= limit) return new Page(rows, null);
        var last = rows.get(limit - 1);
        return new Page(rows.subList(0, limit), new Cursor(last.createdAt, last.id).encode());
    }
}
