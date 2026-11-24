package com.acme.auth.account;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SEC-9: rolling window; authentication accepts exact lowercase usernames without normalization. */
@Component
class LoginThrottle {
    private final int maximum;
    private final long window;
    private final LongSupplier clock;
    private final HashMap<String, ArrayDeque<Long>> attempts = new HashMap<>();
    private long lastCleanup;

    @Autowired
    LoginThrottle(@Value("${auth.login-max-attempts:5}") int maximum,
                  @Value("${auth.login-window:1m}") Duration window) {
        this(maximum, window, System::nanoTime);
    }

    LoginThrottle(int maximum, Duration window, LongSupplier clock) {
        if (maximum < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Login limits must be positive");
        }
        this.maximum = maximum;
        this.window = window.toNanos();
        this.clock = clock;
        this.lastCleanup = clock.getAsLong();
    }

    synchronized void attempt(String username) {
        long now = clock.getAsLong();
        if (now - lastCleanup >= window) {
            attempts.values().removeIf(queue -> now - queue.getLast() >= window);
            lastCleanup = now;
        }
        var queue = attempts.computeIfAbsent(username, ignored -> new ArrayDeque<>());
        while (!queue.isEmpty() && now - queue.getFirst() >= window) queue.removeFirst();
        if (queue.size() >= maximum) {
            long remaining = window - (now - queue.getFirst());
            throw new Limited(1 + (remaining - 1) / 1_000_000_000L);
        }
        queue.addLast(now);
    }

    static final class Limited extends AuthProblems.Failure {
        final long retryAfter;
        Limited(long retryAfter) {
            super(AuthProblems.Kind.RATE_LIMITED);
            this.retryAfter = retryAfter;
        }
    }
}
