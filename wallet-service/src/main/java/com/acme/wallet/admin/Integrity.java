package com.acme.wallet.admin;

import java.math.BigInteger;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Integrity {
    record Check(String id, boolean ok, String detail) {}
    record Totals(BigInteger wallets, long payoutHold, long externalBank) {}
    record Report(Instant checkedAt, boolean ok, List<Check> checks, Totals totals) {}

    private final JdbcClient db;

    Integrity(JdbcClient db) {
        this.db = db;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report() {
        Instant checkedAt = db.sql("select current_timestamp").query(Timestamp.class).single().toInstant();
        var totals = db.sql("""
                select coalesce(sum(balance) filter (where wallet_id is not null),0) wallets,
                       max(balance) filter (where system_name='PAYOUT_HOLD') payout_hold,
                       max(balance) filter (where system_name='EXTERNAL_BANK') external_bank from accounts
                """).query(Totals.class).single();
        var checks = List.of(
                check("INV-1", "unbalanced postings", """
                        select count(*) from (select posting_id from ledger_entries
                        group by grouping sets ((posting_id), ()) having sum(amount) <> 0) bad
                        """),
                check("INV-2", "incorrect account balances", """
                        select count(*) from accounts a left join
                        (select account_id, sum(amount) total from ledger_entries group by account_id) e
                        on e.account_id=a.id where a.balance <> coalesce(e.total,0)
                        """),
                check("INV-3", "negative wallet or hold balances", """
                        select count(*) from accounts where balance < 0 and system_name is distinct from 'EXTERNAL_BANK'
                        """),
                check("INV-4", "hold minus pending withdrawals", """
                        select (select balance from accounts where system_name='PAYOUT_HOLD') - coalesce(sum(amount),0)
                        from bank_operations where kind='WITHDRAWAL' and status='PENDING'
                        """),
                check("INV-5", "external balance minus net succeeded payouts", """
                        select (select balance from accounts where system_name='EXTERNAL_BANK') -
                        coalesce(sum(case when kind='WITHDRAWAL' then amount else -amount end),0)
                        from bank_operations where status='SUCCEEDED'
                        """),
                check("INV-6", "incorrect wallet running balances", """
                        select count(*) from (select balance_after, sum(amount) over
                        (partition by account_id order by e.created_at,e.id) running from ledger_entries e
                        join wallets w on w.id=e.account_id) s where balance_after is distinct from running
                        """));
        return new Report(checkedAt, checks.stream().allMatch(Check::ok), checks, totals);
    }

    private Check check(String id, String description, String sql) {
        var difference = db.sql(sql).query(BigInteger.class).single();
        return new Check(id, difference.signum() == 0, description + " = " + difference);
    }
}
