package com.acme.notification.inbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationCounters {
    private final Counter processed;
    private final Counter duplicates;
    private final Counter dead;

    public NotificationCounters(MeterRegistry registry) {
        processed = registry.counter("notification.events.processed");
        duplicates = registry.counter("notification.duplicates.ignored");
        dead = registry.counter("notification.dead.lettered");
    }
    public void processed(boolean inserted) { (inserted ? processed : duplicates).increment(); }
    public void deadLettered() { dead.increment(); }
    public Stats stats() { return new Stats((long) processed.count(), (long) duplicates.count(), (long) dead.count()); }
    public record Stats(long eventsProcessed, long duplicatesIgnored, long deadLettered) {}
}
