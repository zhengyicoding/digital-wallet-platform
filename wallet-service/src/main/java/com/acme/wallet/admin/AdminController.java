package com.acme.wallet.admin;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import com.acme.wallet.api.WalletProblems;
import com.acme.wallet.bank.Reconciliation;
import com.acme.wallet.ledger.Statements.Cursor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    record Status(long pendingDeposits, long pendingWithdrawals, long oldestPendingSeconds,
                  long outboxUnsent, long outboxOldestSeconds, String bankCircuit) {}
    record Wallet(UUID walletId, String username, String displayName, long balance, long reserved) {}
    record Page(List<Wallet> items, String nextCursor) {}
    private record Row(Wallet wallet, Cursor cursor) {}

    private final StatusQueries statusQueries;
    private final Integrity integrity;
    private final Reconciliation reconciliation;
    private final JdbcClient db;

    AdminController(Integrity integrity, Reconciliation reconciliation, JdbcClient db, StatusQueries statusQueries) {
        this.statusQueries = statusQueries;
        this.integrity = integrity;
        this.reconciliation = reconciliation;
        this.db = db;
    }

    @GetMapping("/integrity")
    Integrity.Report integrity() {
        return integrity.report();
    }

    @GetMapping("/status")
    Status status() {
        var snapshot = statusQueries.snapshot();
        return new Status(snapshot.pendingDeposits(), snapshot.pendingWithdrawals(), snapshot.oldestPendingSeconds(),
                snapshot.outboxUnsent(), snapshot.outboxOldestSeconds(), reconciliation.circuitState());
    }

    @GetMapping("/wallets")
    Page wallets(@RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > 100) throw WalletProblems.invalid("limit", "Use a limit from 1 to 100");
        var sql = """
                select w.*, a.balance, (select coalesce(sum(amount),0) from bank_operations
                where wallet_id=w.id and status='PENDING' and kind='WITHDRAWAL') reserved
                from wallets w join accounts a on a.wallet_id=w.id
                """;
        var query = db.sql(sql + (cursor == null ? "" : " where (w.created_at,w.id)<(:time,:id)")
                + " order by w.created_at desc,w.id desc limit :count").param("count", limit + 1);
        if (cursor != null) {
            var after = Cursor.decode(cursor);
            query = query.param("time", Timestamp.from(after.time())).param("id", after.id());
        }
        var rows = query.query((rs, n) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new Row(new Wallet(id, rs.getString("username"), rs.getString("display_name"),
                    rs.getLong("balance"), rs.getLong("reserved")), new Cursor(rs.getTimestamp("created_at").toInstant(), id));
        }).list();
        String next = rows.size() > limit ? rows.get(limit - 1).cursor.encode() : null;
        return new Page(rows.stream().limit(limit).map(Row::wallet).toList(), next);
    }

    @PostMapping("/reconciliation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void reconcile() {
        reconciliation.allPending();
    }
}
