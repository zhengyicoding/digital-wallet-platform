package com.acme.wallet.observability;

import com.acme.wallet.admin.StatusQueries;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class WalletMetrics {
    public WalletMetrics(MeterRegistry registry, StatusQueries queries) {
        Gauge.builder("wallet.pending.operations", queries, q -> q.snapshot().pendingDeposits())
                .tag("type", "DEPOSIT").register(registry);
        Gauge.builder("wallet.pending.operations", queries, q -> q.snapshot().pendingWithdrawals())
                .tag("type", "WITHDRAWAL").register(registry);
        Gauge.builder("wallet.pending.oldest.age.seconds", queries, q -> q.snapshot().depositAge())
                .tag("type", "DEPOSIT").register(registry);
        Gauge.builder("wallet.pending.oldest.age.seconds", queries, q -> q.snapshot().withdrawalAge())
                .tag("type", "WITHDRAWAL").register(registry);
        Gauge.builder("wallet.outbox.backlog", queries, q -> q.snapshot().outboxUnsent()).register(registry);
        Gauge.builder("wallet.outbox.oldest.age.seconds", queries, q -> q.snapshot().outboxOldestSeconds()).register(registry);
    }
}
