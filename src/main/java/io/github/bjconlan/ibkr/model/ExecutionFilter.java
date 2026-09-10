package io.github.bjconlan.ibkr.model;

import java.util.List;

/** Optional filter for an executions request. */
public record ExecutionFilter(
        Integer clientId,
        String accountCode,
        String time,
        String symbol,
        String secType,
        String exchange,
        String side,
        Integer lastNDays,
        List<Integer> specificDates) {

    public ExecutionFilter {
        specificDates = specificDates == null ? List.of() : List.copyOf(specificDates);
    }

    public static ExecutionFilter all() {
        return new ExecutionFilter(null, null, null, null, null, null, null, null, null);
    }
}
