package com.acme.wallet.transfer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.acme.wallet.api.WalletProblems;
import com.acme.wallet.api.WalletProblems.Failure;
import com.acme.wallet.api.WalletProblems.Kind;
import com.acme.wallet.idempotency.Idempotency;
import com.acme.wallet.ledger.Wallets;
import com.acme.wallet.outbox.Outbox;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class Transfers {
    private final JdbcClient db;
    private final Wallets wallets;
    private final Idempotency idempotency;
    private final Outbox outbox;

    public Transfers(JdbcClient db, Wallets wallets, Idempotency idempotency, Outbox outbox) {
        this.db = db;
        this.wallets = wallets;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    private record Party(UUID walletId, UUID userId, String displayName) {}
    private record Completed(UUID transferId, long amount, String currency, String note, Party from, Party to) {}

    public record Request(UUID toWalletId, Long amount, String note) {}
    public record Transfer(UUID id, UUID fromWalletId, UUID toWalletId, long amount, String currency,
                           String note, String status, Instant createdAt) {}

    public ResponseEntity<byte[]> send(UUID userId, String key, JsonNode body) {
        return idempotency.execute(userId, "/api/v1/transfers", key, body, Request.class, HttpStatus.CREATED, request -> {
            var transfer = post(userId, request);
            return new Idempotency.Resource(transfer.id, transfer);
        });
    }

    private Transfer post(UUID userId, Request request) {
        if (request.toWalletId == null) throw WalletProblems.invalid("toWalletId", "Supply the recipient wallet ID");
        long amount = wallets.amount(request.amount);
        if (request.note != null && (request.note.codePointCount(0, request.note.length()) > 140
                || request.note.indexOf(0) >= 0)) {
            throw WalletProblems.invalid("note", "Use up to 140 text characters");
        }
        UUID from = wallets.id(userId);
        UUID to = request.toWalletId;
        if (from.equals(to)) throw new Failure(Kind.SAME_WALLET);
        var locked = db.sql("""
                select id as wallet_id,user_id,display_name from wallets where id in (?,?) order by id for update
                """).params(from, to).query(Party.class).list();
        if (locked.size() != 2) throw new Failure(Kind.RECIPIENT_NOT_FOUND);

        // A fresh READ COMMITTED statement after acquiring both locks sees every preceding transfer.
        Timestamp now = db.sql("select clock_timestamp()").query(Timestamp.class).single();
        wallets.checkSpending(from, amount, now);

        UUID id = Wallets.newId();
        db.sql("insert into transfers(id,from_wallet_id,to_wallet_id,amount,note,created_at) values (?,?,?,?,?,?)")
                .params(id, from, to, amount, request.note, now).update();
        db.sql("insert into postings(id,reference_type,reference_id) values (?,'TRANSFER',?)").params(id,id).update();
        db.sql("""
                insert into ledger_entries(id, posting_id, account_id, type, amount, counterparty_id, note)
                values (?,?,?,'TRANSFER_OUT',?,?,?), (?,?,?,'TRANSFER_IN',?,?,?)
                """).params(Wallets.newId(), id, from, -amount, to, request.note,
                            Wallets.newId(), id, to, amount, from, request.note).update();
        boolean senderFirst = locked.getFirst().walletId.equals(from);
        outbox.append("transfer.completed", id, now.toInstant(), new Completed(id, amount, "USD", request.note,
                senderFirst ? locked.getFirst() : locked.getLast(), senderFirst ? locked.getLast() : locked.getFirst()));
        return new Transfer(id, from, to, amount, "USD", request.note, "COMPLETED", now.toInstant());
    }

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
