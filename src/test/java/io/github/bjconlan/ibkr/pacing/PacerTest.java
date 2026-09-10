package io.github.bjconlan.ibkr.pacing;

import io.github.bjconlan.ibkr.protocol.OutgoingId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacerTest {

    @Test
    void rateLimitMakesTheSecondCallWaitForTheNextPeriod() {
        Pacer pacer = new Pacer(List.of(), Map.of(
                OutgoingId.REQ_CONTRACT_DATA,
                List.of(new PacingRule.Rate("test.one-per-300ms", 1, Duration.ofMillis(300)))),
                Duration.ofSeconds(5));

        long start = System.nanoTime();
        pacer.acquire(OutgoingId.REQ_CONTRACT_DATA, PacingKeys.NONE);
        pacer.acquire(OutgoingId.REQ_CONTRACT_DATA, PacingKeys.NONE);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis >= 200, "expected to wait for the next period, waited " + elapsedMillis + "ms");
    }

    @Test
    void concurrencyLeaseCapsOutstandingSubscriptionsUntilReleased() {
        Pacer pacer = new Pacer(List.of(), Map.of(
                OutgoingId.REQ_MKT_DATA,
                List.of(new PacingRule.Concurrency("test.lines", 1))),
                Duration.ofSeconds(1));

        Pacer.Lease lease = pacer.acquireLease(OutgoingId.REQ_MKT_DATA);
        assertThrows(PacingViolationException.class, () -> pacer.acquireLease(OutgoingId.REQ_MKT_DATA));

        lease.close();
        assertDoesNotThrow(() -> {
            Pacer.Lease again = pacer.acquireLease(OutgoingId.REQ_MKT_DATA);
            again.close();
        });
    }

    @Test
    void keyedRateSeparatesFingerprintsAndDelaysRepeats() {
        Pacer pacer = new Pacer(List.of(), Map.of(
                OutgoingId.REQ_HISTORICAL_DATA,
                List.of(new PacingRule.KeyedRate("test.identical", 1, Duration.ofMillis(300),
                        PacingRule.KeyedRate.Scope.REQUEST))),
                Duration.ofSeconds(5));

        pacer.acquire(OutgoingId.REQ_HISTORICAL_DATA, new PacingKeys("AAPL", "AAPL"));
        assertDoesNotThrow(() -> pacer.acquire(OutgoingId.REQ_HISTORICAL_DATA, new PacingKeys("MSFT", "MSFT")));

        long start = System.nanoTime();
        pacer.acquire(OutgoingId.REQ_HISTORICAL_DATA, new PacingKeys("AAPL", "AAPL"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis >= 200, "expected the repeated key to wait, waited " + elapsedMillis + "ms");
    }

    @Test
    void scopeKeyedRuleIgnoresRequestKey() {
        Pacer pacer = new Pacer(List.of(), Map.of(
                OutgoingId.REQ_HISTORICAL_DATA,
                List.of(new PacingRule.KeyedRate("test.scope", 1, Duration.ofSeconds(30),
                        PacingRule.KeyedRate.Scope.CONTRACT_EXCHANGE_TICK_TYPE))),
                Duration.ofSeconds(1));

        // Same contract/exchange/tick type, different full requests: the second must wait.
        pacer.acquire(OutgoingId.REQ_HISTORICAL_DATA, new PacingKeys("AAPL|1D|5mins", "AAPL|TRADES"));
        assertThrows(PacingViolationException.class, () ->
                pacer.acquire(OutgoingId.REQ_HISTORICAL_DATA, new PacingKeys("AAPL|1W|1hour", "AAPL|TRADES")));
    }
}
