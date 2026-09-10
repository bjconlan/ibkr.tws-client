package io.github.bjc.ibkr.pacing;

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
                RequestType.CONTRACT_DETAILS,
                List.of(new PacingRule.Rate("test.one-per-300ms", 1, Duration.ofMillis(300)))),
                Duration.ofSeconds(5));

        long start = System.nanoTime();
        pacer.acquire(RequestType.CONTRACT_DETAILS, null);
        pacer.acquire(RequestType.CONTRACT_DETAILS, null);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis >= 200, "expected to wait for the next period, waited " + elapsedMillis + "ms");
    }

    @Test
    void concurrencyLeaseCapsOutstandingSubscriptionsUntilReleased() {
        Pacer pacer = new Pacer(List.of(), Map.of(
                RequestType.MARKET_DATA,
                List.of(new PacingRule.Concurrency("test.lines", 1))),
                Duration.ofSeconds(1));

        Pacer.Lease lease = pacer.acquireLease(RequestType.MARKET_DATA);
        assertThrows(PacingViolationException.class, () -> pacer.acquireLease(RequestType.MARKET_DATA));

        lease.close();
        assertDoesNotThrow(() -> {
            Pacer.Lease again = pacer.acquireLease(RequestType.MARKET_DATA);
            again.close();
        });
    }

    @Test
    void keyedRateSeparatesFingerprintsAndDelaysRepeats() {
        Pacer pacer = new Pacer(List.of(), Map.of(
                RequestType.HISTORICAL_DATA,
                List.of(new PacingRule.KeyedRate("test.identical", 1, Duration.ofMillis(300)))),
                Duration.ofSeconds(5));

        pacer.acquire(RequestType.HISTORICAL_DATA, "AAPL");
        assertDoesNotThrow(() -> pacer.acquire(RequestType.HISTORICAL_DATA, "MSFT"));

        long start = System.nanoTime();
        pacer.acquire(RequestType.HISTORICAL_DATA, "AAPL");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis >= 200, "expected the repeated key to wait, waited " + elapsedMillis + "ms");
    }
}
