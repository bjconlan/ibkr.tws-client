package io.github.bjconlan.ibkr.pacing;

import io.github.bjconlan.ibkr.protocol.OutgoingId;
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
 * Enforces the {@link PacingRule}s attached to {@link OutgoingId}s.
 *
 * <p>Rate limits are backed by resilience4j {@link RateLimiter}s, which share state by name so
 * that two requests of the same kind draw from the same budget. Subscription limits are backed by
 * resilience4j {@link Bulkhead}s: a caller takes a {@link Lease} when it subscribes and closes the
 * lease when it unsubscribes.
 *
 * <p>Callers may block while waiting for a rate permit. That is intentional - blocking a virtual
 * thread is cheap, and it means callers do not have to implement their own back-off. If the wait
 * exceeds {@link #acquireTimeout()}, a {@link PacingViolationException} is thrown rather than
 * sending a request IBKR would reject.
 */
public final class Pacer {

    private static final System.Logger LOG = System.getLogger(Pacer.class.getName());

    /** How long a caller will wait for a rate permit before giving up. */
    public static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(30);

    private final List<PacingRule> globalRules;
    private final Map<OutgoingId, List<PacingRule>> rules;
    private final Duration acquireTimeout;
    private final SubscriptionBudget budget;

    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> keyedLimiters = new ConcurrentHashMap<>();

    public Pacer(List<PacingRule> globalRules,
                 Map<OutgoingId, List<PacingRule>> rules,
                 Duration acquireTimeout) {
        this(globalRules, rules, acquireTimeout, new SubscriptionBudget());
    }

    /** As above, sharing {@code budget}'s subscription permits with other pacers. */
    public Pacer(List<PacingRule> globalRules,
                 Map<OutgoingId, List<PacingRule>> rules,
                 Duration acquireTimeout,
                 SubscriptionBudget budget) {
        this.globalRules = List.copyOf(globalRules);
        this.rules = Map.copyOf(rules);
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /** A pacer with the documented limits for {@code marketDataLines} lines and default timeout. */
    public static Pacer of(int marketDataLines) {
        return of(marketDataLines, new SubscriptionBudget());
    }

    /**
     * A pacer with per-connection rate limits that draw subscription permits from a shared
     * {@code budget}. Use this for the connections of a pool.
     */
    public static Pacer of(int marketDataLines, SubscriptionBudget budget) {
        return new Pacer(PacingRules.global(marketDataLines),
                PacingRules.standard(marketDataLines),
                DEFAULT_ACQUIRE_TIMEOUT,
                budget);
    }

    /** A pacer for the default entitlement of 100 market data lines. */
    public static Pacer standard() {
        return of(100);
    }

    public Duration acquireTimeout() {
        return acquireTimeout;
    }

    /**
     * Blocks until a request of {@code id} may be sent. {@code keys} supplies the fingerprints
     * needed by {@link PacingRule.KeyedRate} rules; use {@link PacingKeys#NONE} when none apply.
     *
     * @throws PacingViolationException if a permit could not be obtained in time
     */
    public void acquire(OutgoingId id, PacingKeys keys) {
        for (PacingRule rule : globalRules) {
            admit(rule, id, keys);
        }
        for (PacingRule rule : rules.getOrDefault(id, List.of())) {
            if (rule instanceof PacingRule.Concurrency) {
                // Held permits are taken through acquireLease and are not rate admissions.
                continue;
            }
            admit(rule, id, keys);
        }
    }

    /**
     * Takes a held permit for a subscription-style limit. The returned lease must be closed
     * once the subscription ends.
     *
     * @throws PacingViolationException if no permit is currently free
     */
    public Lease acquireLease(OutgoingId id) {
        Bulkhead bulkhead = null;
        for (PacingRule rule : rules.getOrDefault(id, List.of())) {
            if (rule instanceof PacingRule.Concurrency concurrency) {
                bulkhead = budget.bulkheads().computeIfAbsent(concurrency.name(),
                        name -> Bulkhead.of(name, BulkheadConfig.custom()
                                .maxConcurrentCalls(concurrency.permits())
                                .build()));
                if (!bulkhead.tryAcquirePermission()) {
                    LOG.log(System.Logger.Level.DEBUG, "pacing violation: %s limit reached (%d) for %s"
                            .formatted(concurrency.name(), concurrency.permits(), id));
                    throw new PacingViolationException(concurrency.name(), id,
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

    private void admit(PacingRule rule, OutgoingId id, PacingKeys keys) {
        switch (rule) {
            case PacingRule.Rate rate -> acquire(limiter(rate), rate.name(), id);
            case PacingRule.KeyedRate keyed -> {
                String fingerprint = switch (keyed.scope()) {
                    case REQUEST -> keys.request();
                    case CONTRACT_EXCHANGE_TICK_TYPE -> keys.scope();
                };
                if (fingerprint == null || fingerprint.isBlank()) {
                    return;
                }
                acquire(keyedLimiter(keyed, fingerprint), keyed.name(), id);
            }
            case PacingRule.Concurrency ignored -> {
                // handled by acquireLease
            }
        }
    }

    private void acquire(RateLimiter limiter, String name, OutgoingId id) {
        if (!limiter.acquirePermission()) {
            LOG.log(System.Logger.Level.DEBUG, "pacing violation: waited %s for a permit from %s (%s)"
                    .formatted(acquireTimeout, name, id));
            throw new PacingViolationException(name, id,
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
