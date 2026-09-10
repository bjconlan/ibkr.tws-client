package io.github.bjconlan.ibkr.pacing;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the {@link PacingRule}s attached to {@link RequestType}s.
 *
 * <p>Rate limits are backed by resilience4j {@link RateLimiter}s, which share state by name so
 * that two requests of the same type draw from the same budget. Subscription limits are backed by
 * resilience4j {@link Bulkhead}s: a caller takes a {@link Lease} when it subscribes and closes the
 * lease when it unsubscribes.
 *
 * <p>Callers may block while waiting for a rate permit. That is intentional - blocking a virtual
 * thread is cheap, and it means callers do not have to implement their own back-off. If the wait
 * exceeds {@link #acquireTimeout()}, a {@link PacingViolationException} is thrown rather than
 * sending a request IBKR would reject.
 */
public final class Pacer {

    /** How long a caller will wait for a rate permit before giving up. */
    public static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(30);

    private final List<PacingRule> globalRules;
    private final Map<RequestType, List<PacingRule>> rules;
    private final Duration acquireTimeout;

    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> keyedLimiters = new ConcurrentHashMap<>();
    private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();

    public Pacer(List<PacingRule> globalRules,
                 Map<RequestType, List<PacingRule>> rules,
                 Duration acquireTimeout) {
        this.globalRules = List.copyOf(globalRules);
        this.rules = Map.copyOf(rules);
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
    }

    /** A pacer configured with {@link PacingRules#defaults()}. */
    public static Pacer standard() {
        return new Pacer(PacingRules.GLOBAL, PacingRules.defaults(), DEFAULT_ACQUIRE_TIMEOUT);
    }

    public Duration acquireTimeout() {
        return acquireTimeout;
    }

    /**
     * Blocks until {@code type} may be sent. {@code fingerprint} distinguishes individual
     * requests for {@link PacingRule.KeyedRate} rules and may be {@code null} when the caller
     * knows no keyed rule applies.
     *
     * @throws PacingViolationException if a permit could not be obtained in time
     */
    public void acquire(RequestType type, String fingerprint) {
        for (PacingRule rule : globalRules) {
            admit(rule, type, fingerprint);
        }
        for (PacingRule rule : rules.getOrDefault(type, List.of())) {
            if (rule instanceof PacingRule.Concurrency) {
                // Held permits are taken through acquireLease and are not rate admissions.
                continue;
            }
            admit(rule, type, fingerprint);
        }
    }

    /**
     * Takes a held permit for a subscription-style limit. The returned lease must be closed
     * once the subscription ends.
     *
     * @throws PacingViolationException if no permit is currently free
     */
    public Lease acquireLease(RequestType type) {
        Bulkhead bulkhead = null;
        for (PacingRule rule : rules.getOrDefault(type, List.of())) {
            if (rule instanceof PacingRule.Concurrency concurrency) {
                bulkhead = bulkheads.computeIfAbsent(concurrency.name(),
                        name -> Bulkhead.of(name, BulkheadConfig.custom()
                                .maxConcurrentCalls(concurrency.permits())
                                .build()));
                if (!bulkhead.tryAcquirePermission()) {
                    throw new PacingViolationException(concurrency.name(), type,
                            "%s limit reached (%d)".formatted(concurrency.name(), concurrency.permits()));
                }
            }
        }
        if (bulkhead == null) {
            return Lease.noop();
        }
        Bulkhead acquired = bulkhead;
        return new Lease(acquired.getName(), acquired::releasePermission);
    }

    private void admit(PacingRule rule, RequestType type, String fingerprint) {
        switch (rule) {
            case PacingRule.Rate rate -> acquire(limiter(rate), rate.name(), type);
            case PacingRule.KeyedRate keyed -> {
                if (fingerprint == null || fingerprint.isBlank()) {
                    return;
                }
                acquire(keyedLimiter(keyed, fingerprint), keyed.name(), type);
            }
            case PacingRule.Concurrency ignored -> {
                // handled by acquireLease
            }
        }
    }

    private void acquire(RateLimiter limiter, String name, RequestType type) {
        if (!limiter.acquirePermission()) {
            throw new PacingViolationException(name, type,
                    "waited %s for a permit from %s".formatted(acquireTimeout, name));
        }
    }

    private RateLimiter limiter(PacingRule.Rate rate) {
        return rateLimiters.computeIfAbsent(rate.name(), name -> RateLimiter.of(name, config(rate.permits(), rate.period())));
    }

    private RateLimiter keyedLimiter(PacingRule.KeyedRate rule, String fingerprint) {
        String key = rule.name() + '\u0000' + fingerprint;
        return keyedLimiters.computeIfAbsent(key,
                ignored -> RateLimiter.of(rule.name(), config(rule.permits(), rule.period())));
    }

    private RateLimiterConfig config(int permits, Duration period) {
        return RateLimiterConfig.custom()
                .limitForPeriod(permits)
                .limitRefreshPeriod(period)
                .timeoutDuration(acquireTimeout)
                .build();
    }

    /** A held permit for a subscription-style limit. */
    public record Lease(String name, Runnable onRelease) implements AutoCloseable {

        private static final Lease NOOP = new Lease("none", () -> { });

        static Lease noop() {
            return NOOP;
        }

        @Override
        public void close() {
            onRelease.run();
        }
    }
}
