package io.github.bjc.ibkr.model;

/** A row of the account portfolio, including unrealized and realized P&L. */
public record PortfolioValue(
        Contract contract,
        String position,
        Double marketPrice,
        Double marketValue,
        Double averageCost,
        Double unrealizedPnl,
        Double realizedPnl,
        String accountName) {
}
