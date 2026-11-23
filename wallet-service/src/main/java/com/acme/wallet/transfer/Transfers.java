package com.acme.wallet.transfer;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.acme.wallet.api.WalletProblems;
import com.acme.wallet.api.WalletProblems.Failure;
import com.acme.wallet.api.WalletProblems.Kind;
import com.acme.wallet.ledger.Wallets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Transfers {
    private final JdbcClient db;
    private final Wallets wallets;
    private final TransactionTemplate transaction;
    private final long minAmount;
    private final long maxAmount;
    private final long dailyLimit;

    public Transfers(JdbcClient db, Wallets wallets, PlatformTransactionManager manager,
                     @Value("${wallet.min-amount}") long minAmount, @Value("${wallet.max-amount}") long maxAmount,
                     @Value("${wallet.daily-limit}") long dailyLimit) {
        this.db = db;
        this.wallets = wallets;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.dailyLimit = dailyLimit;
    }

    public record Request(UUID toWalletId, Long amount, String note) {}
    public record Transfer(UUID id, UUID fromWalletId, UUID toWalletId, long amount, String currency,
                           String note, String status, Instant createdAt) {}

    public Transfer send(UUID userId, Request request) {
        if (request.toWalletId == null) throw WalletProblems.invalid("toWalletId", "Supply the recipient wallet ID");
        if (request.amount == null || request.amount < minAmount || request.amount > maxAmount) {
            throw WalletProblems.invalid("amount", "Amount is outside the allowed range");
        }
        if (request.note != null && (request.note.codePointCount(0, request.note.length()) > 140
                || request.note.indexOf(0) >= 0)) {
            throw WalletProblems.invalid("note", "Use up to 140 text characters");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.execute(status -> post(userId, request));
            } catch (RuntimeException failure) {
                if (attempt >= 3 || !retryable(failure)) throw failure;
            }
        }
    }

    private static boolean retryable(Throwable cause) {
        for (; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql &&
                    ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) return true;
        }
        return false;
    }

    private Transfer post(UUID userId, Request request) {
        UUID from = wallets.id(userId);
        UUID to = request.toWalletId;
        if (from.equals(to)) throw new Failure(Kind.SAME_WALLET);
        var locked = db.sql("select id from wallets where id in (?,?) order by id for update")
                .params(from, to).query(UUID.class).list();
        if (locked.size() != 2) throw new Failure(Kind.RECIPIENT_NOT_FOUND);

        // A fresh READ COMMITTED statement after acquiring both locks sees every preceding transfer.
        Timestamp now = db.sql("select clock_timestamp()").query(Timestamp.class).single();
        var funds = db.sql("select a.balance, " + Wallets.SENT_TODAY + """
                 as sent from wallets w join accounts a on a.wallet_id=w.id where w.id=:id
                """).param("asOf", now).param("id", from)
                .query((rs, n) -> new Funds(rs.getLong("balance"), rs.getLong("sent"))).single();
        long amount = request.amount;
        if (funds.balance < amount) throw new Failure(Kind.INSUFFICIENT_FUNDS);
        if (dailyLimit - funds.sent < amount) throw new Failure(Kind.DAILY_LIMIT_EXCEEDED);

        UUID id = Wallets.newId();
        db.sql("insert into transfers(id,from_wallet_id,to_wallet_id,amount,note,created_at) values (?,?,?,?,?,?)")
                .params(id, from, to, amount, request.note, now).update();
        db.sql("insert into postings(id,reference_type,reference_id) values (?,'TRANSFER',?)").params(id,id).update();
        db.sql("""
                insert into ledger_entries(id, posting_id, account_id, type, amount, counterparty_id, note)
                values (?,?,?,'TRANSFER_OUT',?,?,?), (?,?,?,'TRANSFER_IN',?,?,?)
                """).params(Wallets.newId(), id, from, -amount, to, request.note,
                            Wallets.newId(), id, to, amount, from, request.note).update();
        return new Transfer(id, from, to, amount, "USD", request.note, "COMPLETED", now.toInstant());
    }

    private record Funds(long balance, long sent) {}

    public Transfer get(UUID userId, UUID id) {
        return db.sql("""
                select t.* from transfers t join wallets w on w.id in (t.from_wallet_id,t.to_wallet_id)
                where t.id=? and w.user_id=?
                """).params(id, userId).query((rs, n) -> new Transfer(id, rs.getObject("from_wallet_id", UUID.class),
                rs.getObject("to_wallet_id", UUID.class), rs.getLong("amount"), "USD", rs.getString("note"),
                "COMPLETED", rs.getTimestamp("created_at").toInstant())).optional()
                .orElseThrow(() -> new Failure(Kind.NOT_FOUND));
    }
}
