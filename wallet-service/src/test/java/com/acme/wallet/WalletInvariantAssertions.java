package com.acme.wallet;

import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

public final class WalletInvariantAssertions {
    private WalletInvariantAssertions() {}

    private static long scalar(JdbcClient db, String sql) {
        return db.sql(sql).query(Long.class).single();
    }

    public static void verify(JdbcClient db, long fixtureFunding) {
        // LED-5, INV-1..6. All writers have joined before reading committed rows.
        assertThat(scalar(db, "select coalesce(sum(amount),0) from ledger_entries")).isZero();
        assertThat(scalar(db, """
                select count(*) from (select p.id from postings p left join ledger_entries e on p.id=e.posting_id
                group by p.id having count(e.id)<>2 or sum(e.amount)<>0) invalid
                """)).isZero();
        assertThat(scalar(db, """
                select count(*) from accounts a where balance <>
                (select coalesce(sum(amount),0) from ledger_entries where account_id=a.id)
                """)).isZero();
        assertThat(scalar(db, "select count(*) from accounts where system_name is distinct from 'EXTERNAL_BANK' and balance<0")).isZero();
        assertThat(scalar(db, "select balance from accounts where system_name='PAYOUT_HOLD'")).isEqualTo(scalar(db,
                "select coalesce(sum(amount),0) from bank_operations where kind='WITHDRAWAL' and status='PENDING'"));
        assertThat(scalar(db, "select balance from accounts where system_name='EXTERNAL_BANK'")).isEqualTo(scalar(db, """
                select coalesce(sum(case when kind='DEPOSIT' then -amount else amount end),0)
                from bank_operations where status='SUCCEEDED'
                """) - fixtureFunding);
        assertThat(scalar(db, """
                select count(*) from (select balance_after,sum(amount) over
                (partition by account_id order by e.created_at,e.id) running from ledger_entries e
                join accounts a on a.id=e.account_id where a.wallet_id is not null) s
                where balance_after is distinct from running
                """)).isZero();
    }
}
