package com.acme.wallet.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Shared by ADM-2 and OPS-5; aggregates stay correct across instances and restarts. */
@Component
public class StatusQueries {
    public record Snapshot(long pendingDeposits, long pendingWithdrawals, long depositAge, long withdrawalAge,
                           long outboxUnsent, long outboxOldestSeconds) {
        public long oldestPendingSeconds() { return Math.max(depositAge, withdrawalAge); }
    }
    private final JdbcClient db;
    public StatusQueries(JdbcClient db) { this.db = db; }
    public Snapshot snapshot() {
        return db.sql("""
                select p.*, o.* from (
                    select count(*) filter (where kind='DEPOSIT') pending_deposits,
                           count(*) filter (where kind='WITHDRAWAL') pending_withdrawals,
                           greatest(0, floor(extract(epoch from now()-(min(created_at)
                               filter (where kind='DEPOSIT'))))) deposit_age,
                           greatest(0, floor(extract(epoch from now()-(min(created_at)
                               filter (where kind='WITHDRAWAL'))))) withdrawal_age
                    from bank_operations where status='PENDING'
                ) p cross join (
                    select count(*) outbox_unsent, greatest(0, floor(extract(epoch from
                           now()-min((envelope->>'occurredAt')::timestamptz)))) outbox_oldest_seconds
                    from outbox where sent_at is null
                ) o
                """).query(Snapshot.class).single();
    }
}
