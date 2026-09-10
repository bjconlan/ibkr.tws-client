package io.github.bjconlan.ibkr.model;

/** Commission and fee report attached to an execution. */
public record CommissionReport(
        String execId,
        double commissionAndFees,
        String currency,
        Double realizedPnl,
        Double bondYield,
        Integer yieldRedemptionDate) {
}
