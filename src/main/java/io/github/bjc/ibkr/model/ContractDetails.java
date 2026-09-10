package io.github.bjc.ibkr.model;

import java.util.Map;

/**
 * Descriptive attributes for a {@link Contract}, as returned by a contract details request.
 * This is the commonly used subset of the TWS contract details message.
 */
public record ContractDetails(
        Contract contract,
        String marketName,
        String minTick,
        String orderTypes,
        String validExchanges,
        Integer priceMagnifier,
        Integer underConId,
        String longName,
        String contractMonth,
        String industry,
        String category,
        String subcategory,
        String timeZoneId,
        String tradingHours,
        String liquidHours,
        String evRule,
        Double evMultiplier,
        Map<String, String> secIdList,
        Integer aggGroup,
        String underSymbol,
        String underSecType,
        String marketRuleIds,
        String realExpirationDate,
        String stockType,
        String cusip,
        String bondType,
        String descAppend,
        String notes,
        String settlementMethod) {

    public ContractDetails {
        secIdList = secIdList == null ? Map.of() : Map.copyOf(secIdList);
    }
}
