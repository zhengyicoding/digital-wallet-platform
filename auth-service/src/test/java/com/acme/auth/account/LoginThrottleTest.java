package com.acme.auth.account;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** SEC-9: deterministic monotonic-clock tests for rolling and configurable limits. */
class LoginThrottleTest {
    @Test void SEC9_configuredLimitExpiresEachAttemptSeparately() {
        var now = new AtomicLong();
        var throttle = new LoginThrottle(2, Duration.ofSeconds(10), now::get);
        throttle.attempt("alice");
        now.set(Duration.ofSeconds(5).toNanos());
        throttle.attempt("alice");
        assertThatThrownBy(() -> throttle.attempt("alice")).isInstanceOf(LoginThrottle.Limited.class);
        now.set(Duration.ofSeconds(10).toNanos());
        throttle.attempt("alice");
        now.set(Duration.ofSeconds(14).toNanos());
        assertThatThrownBy(() -> throttle.attempt("alice")).isInstanceOfSatisfying(
                LoginThrottle.Limited.class, ex -> assertThat(ex.retryAfter).isOne());
        now.set(Duration.ofSeconds(15).toNanos());
        throttle.attempt("alice");
    }

    @Test void SEC9_rollingExpiryAndIndependentUsernames() {
        var now = new AtomicLong();
        var throttle = new LoginThrottle(5, Duration.ofMinutes(1), now::get);
        for (int i = 0; i < 5; i++) throttle.attempt("alice");
        assertThatThrownBy(() -> throttle.attempt("alice")).isInstanceOfSatisfying(
                LoginThrottle.Limited.class, ex -> assertThat(ex.retryAfter).isEqualTo(60));
        throttle.attempt("bob");
        now.set(Duration.ofSeconds(59).toNanos());
        assertThatThrownBy(() -> throttle.attempt("alice")).isInstanceOfSatisfying(
                LoginThrottle.Limited.class, ex -> assertThat(ex.retryAfter).isOne());
        now.set(Duration.ofMinutes(1).toNanos());
        throttle.attempt("alice");
    }
    @Test void SEC9_concurrentAttemptsCannotExceedThreshold() throws Exception {
        var throttle = new LoginThrottle(5, Duration.ofMinutes(1), () -> 0);
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(20)) {
            var results = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) results.add(pool.submit(() -> {
                try { start.await(); throttle.attempt("alice"); accepted.incrementAndGet(); }
                catch (LoginThrottle.Limited expected) { }
                catch (InterruptedException ex) { throw new RuntimeException(ex); }
            }));
            start.countDown();
            for (var result : results) result.get(5, TimeUnit.SECONDS);
        }
        assertThat(accepted.get()).isEqualTo(5);
    }
}
