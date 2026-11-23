package com.acme.wallet.ledger;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.acme.wallet.api.WalletProblems;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class Statements {
    public enum EntryType { DEPOSIT, TRANSFER_OUT, TRANSFER_IN, WITHDRAWAL, WITHDRAWAL_REVERSAL }
    public record Counterparty(UUID walletId, String displayName) {}
    public record Entry(UUID id, String type, long amount, long balanceAfter, Counterparty counterparty, String note,
                        String referenceType, UUID referenceId, Instant createdAt) {}
    public record Page(List<Entry> items, String nextCursor) {}

    public record Cursor(Instant time, UUID id) {
        public String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (time + "/" + id).getBytes(StandardCharsets.US_ASCII));
        }

        public static Cursor decode(String value) {
            try {
                if (value.length() > 128) throw new IllegalArgumentException();
                String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split("/", -1);
                if (fields.length != 2) throw new IllegalArgumentException();
                var cursor = new Cursor(Instant.parse(fields[0]), UUID.fromString(fields[1]));
                if (!cursor.encode().equals(value)) throw new IllegalArgumentException();
                return cursor;
            } catch (RuntimeException ex) {
                throw WalletProblems.invalid("cursor", "Invalid cursor");
            }
        }
    }

    private final JdbcClient db;
    private final Wallets wallets;

    public Statements(JdbcClient db, Wallets wallets) {
        this.db = db;
        this.wallets = wallets;
    }

    public Page list(UUID userId, EntryType type, int limit, String token) {
        if (limit < 1 || limit > 100) throw WalletProblems.invalid("limit", "Use a limit from 1 to 100");
        var cursor = token == null ? null : Cursor.decode(token);
        String sql = """
                select e.*,p.reference_type,p.reference_id,w.display_name from ledger_entries e
                join postings p on p.id=e.posting_id left join wallets w on w.id=e.counterparty_id
                where e.account_id=:wallet
                """ + (type == null ? "" : " and e.type=:type")
                + (cursor == null ? "" : " and (e.created_at,e.id)<(:time,:id)")
                + " order by e.created_at desc,e.id desc limit :count";
        var query = db.sql(sql).param("wallet", wallets.id(userId)).param("count", limit + 1);
        if (type != null) query = query.param("type", type.name());
        if (cursor != null) query = query.param("time", Timestamp.from(cursor.time)).param("id", cursor.id);
        var rows = query.query((rs, n) -> {
            UUID other = rs.getObject("counterparty_id", UUID.class);
            return new Entry(rs.getObject("id", UUID.class), rs.getString("type"), rs.getLong("amount"),
                    rs.getLong("balance_after"), other == null ? null : new Counterparty(other, rs.getString("display_name")),
                    rs.getString("note"), rs.getString("reference_type"), rs.getObject("reference_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant());
        }).list();
        if (rows.size() <= limit) return new Page(rows, null);
        var last = rows.get(limit - 1);
        return new Page(rows.subList(0, limit), new Cursor(last.createdAt, last.id).encode());
    }
}
