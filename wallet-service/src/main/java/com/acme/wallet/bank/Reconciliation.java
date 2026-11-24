package com.acme.wallet.bank;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class Reconciliation {
    private final JdbcClient db;
    private final BankClient bank;
    private final Duration pendingAge;

    Reconciliation(JdbcClient db, BankClient bank, @Value("${wallet.reconciliation.pending-age}") Duration pendingAge) {
        this.db = db;
        this.bank = bank;
        this.pendingAge = pendingAge;
    }

    @Scheduled(fixedRateString = "${wallet.reconciliation.interval}")
    public void scheduled() {
        dispatch(db.sql("""
                select id, kind, amount from bank_operations where status='PENDING'
                and created_at < current_timestamp - ? * interval '1 millisecond'
                """).param(pendingAge.toMillis()));
    }

    public void allPending() {
        dispatch(db.sql("select id, kind, amount from bank_operations where status='PENDING'"));
    }

    private void dispatch(JdbcClient.StatementSpec query) {
        query.query((rs, n) -> new BankOperations.Submission(rs.getObject("id", UUID.class),
                BankOperations.OperationKind.valueOf(rs.getString("kind")).bankType, rs.getLong("amount")))
                .list().forEach(bank::reconcile);
    }

    public String circuitState() {
        return bank.circuitState();
    }
}
