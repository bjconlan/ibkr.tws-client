package io.github.bjc.ibkr.model;

/** A status update for a single order. */
public record OrderStatusData(
        int orderId,
        String status,
        String filled,
        String remaining,
        Double avgFillPrice,
        Long permId,
        Integer parentId,
        Double lastFillPrice,
        Integer clientId,
        String whyHeld,
        Double marketCapPrice) {
}
