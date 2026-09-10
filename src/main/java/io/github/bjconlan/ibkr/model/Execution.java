package io.github.bjconlan.ibkr.model;

/** A fill, as reported by the executions feed. */
public record Execution(
        int orderId,
        Integer clientId,
        String execId,
        String time,
        String accountNumber,
        String exchange,
        String side,
        String shares,
        Double price,
        Long permId,
        boolean liquidation,
        String cumulativeQuantity,
        Double averagePrice,
        String orderRef,
        String modelCode,
        Integer lastLiquidity,
        String submitter) {
}
