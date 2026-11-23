package com.acme.wallet.ledger;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.acme.wallet.api.WalletProblems;
import com.acme.wallet.api.WalletProblems.Failure;
import com.acme.wallet.api.WalletProblems.Kind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Wallets {
    private final JdbcClient db;
    private final long dailyLimit;
    private final long minAmount;
    private final long maxAmount;

    public Wallets(JdbcClient db, @Value("${wallet.daily-limit}") long dailyLimit,
                  @Value("${wallet.min-amount}") long minAmount, @Value("${wallet.max-amount}") long maxAmount) {
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.db = db;
        this.dailyLimit = dailyLimit;
    }

    public record Wallet(UUID id, String username, String displayName, String currency, long balance,
                         long reserved, long dailyLimit, long sentToday, Instant createdAt) {}
    public record Opened(Wallet wallet, boolean created) {}
    public record Recipient(UUID walletId, String username, String displayName) {}

    public static UUID newId() {
        UUID random = UUID.randomUUID();
        return new UUID((System.currentTimeMillis() << 16) | 0x7000 | (random.getMostSignificantBits() & 0xfff),
                random.getLeastSignificantBits());
    }

    @Transactional
    public Opened open(UUID userId, String username, String name) {
        UUID id = newId();
        boolean created = db.sql("""
                insert into wallets(id,user_id,username,display_name) values (?,?,?,?)
                on conflict do nothing
                """).params(id, userId, username, name).update() == 1;
        if (created) {
            db.sql("insert into accounts(id,wallet_id) values (?,?)").params(id, id).update();
        }
        return new Opened(details(userId), created);
    }

    public long amount(Long value) {
        if (value == null || value < minAmount || value > maxAmount) {
            throw WalletProblems.invalid("amount", "Amount is outside the allowed range");
        }
        return value;
    }

    private record Funds(long balance, long sent) {}

    // Caller holds the wallet row lock. Read the latest committed spending after acquiring it.
    public void checkSpending(UUID wallet, long amount, Timestamp now) {
        var funds = db.sql("select a.balance, " + SENT_TODAY + """
                 as sent from wallets w join accounts a on a.wallet_id=w.id where w.id=:id
                """).param("asOf", now).param("id", wallet)
                .query((rs, n) -> new Funds(rs.getLong("balance"), rs.getLong("sent"))).single();
        if (funds.balance < amount) throw new Failure(Kind.INSUFFICIENT_FUNDS);
        if (dailyLimit - funds.sent < amount) throw new Failure(Kind.DAILY_LIMIT_EXCEEDED);
    }

    private static final String SENT_TODAY = """
            (select coalesce(sum(t.amount),0) from transfers t where t.from_wallet_id=w.id
             and t.created_at >= date_trunc('day', :asOf, 'UTC')
             and t.created_at < date_trunc('day', :asOf, 'UTC') + interval '24 hours')
            + (select coalesce(sum(o.amount),0) from bank_operations o
               where o.wallet_id=w.id and o.kind='WITHDRAWAL' and o.status<>'FAILED'
               and o.created_at >= date_trunc('day', :asOf, 'UTC')
               and o.created_at < date_trunc('day', :asOf, 'UTC') + interval '24 hours')
            """;

    public Wallet details(UUID userId) {
        String sql = """
                select w.id,w.username,w.display_name,w.created_at,a.balance,
                (select coalesce(sum(amount),0) from bank_operations where wallet_id=w.id
                 and kind='WITHDRAWAL' and status='PENDING') as reserved,
                """ + SENT_TODAY.replace(":asOf", "statement_timestamp()") + """
                 as sent_today from wallets w join accounts a on a.wallet_id=w.id where w.user_id=?
                """;
        return db.sql(sql).param(userId).query((rs, n) -> new Wallet(rs.getObject("id", UUID.class),
                rs.getString("username"), rs.getString("display_name"), "USD", rs.getLong("balance"),
                rs.getLong("reserved"), dailyLimit, rs.getLong("sent_today"), rs.getTimestamp("created_at").toInstant()))
                .optional().orElseThrow(() -> new Failure(Kind.WALLET_NOT_FOUND));
    }

    public UUID id(UUID userId) {
        return db.sql("select id from wallets where user_id=?").param(userId).query(UUID.class)
                .optional().orElseThrow(() -> new Failure(Kind.WALLET_NOT_FOUND));
    }

    public Recipient lookup(String username) {
        return db.sql("select id as wallet_id,username,display_name from wallets where username=?")
                .param(username).query(Recipient.class).optional().orElseThrow(() -> new Failure(Kind.WALLET_NOT_FOUND));
    }
}
