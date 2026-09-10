package io.github.bjc.ibkr.model;

import java.util.List;
import java.util.Objects;

/**
 * The identity of a tradable instrument. Optional fields use {@code null} to mean "not
 * specified", which maps directly to the proto3 {@code optional} fields on the wire.
 */
public record Contract(
        Integer conId,
        String symbol,
        String secType,
        String lastTradeDateOrContractMonth,
        Double strike,
        String right,
        Double multiplier,
        String exchange,
        String primaryExchange,
        String currency,
        String localSymbol,
        String tradingClass,
        String secIdType,
        String secId,
        boolean includeExpired,
        List<ComboLeg> comboLegs,
        DeltaNeutralContract deltaNeutralContract) {

    public Contract {
        comboLegs = comboLegs == null ? List.of() : List.copyOf(comboLegs);
    }

    public static Contract stock(String symbol) {
        return new Contract(null, symbol, "STK", null, null, null, null, "SMART", null, "USD",
                null, null, null, null, false, null, null);
    }

    public static Contract stock(String symbol, String exchange, String currency) {
        return new Contract(null, symbol, "STK", null, null, null, null, exchange, null, currency,
                null, null, null, null, false, null, null);
    }

    public static Contract option(String symbol, String expiry, double strike, String right) {
        return new Contract(null, symbol, "OPT", expiry, strike, right, 100.0, "SMART", null, "USD",
                null, null, null, null, false, null, null);
    }

    public static Contract future(String symbol, String expiry) {
        return new Contract(null, symbol, "FUT", expiry, null, null, null, "CME", null, "USD",
                null, null, null, null, false, null, null);
    }

    public static Contract forex(String pair) {
        String[] parts = pair.split("\\.");
        String symbol = parts.length == 2 ? parts[0] : pair;
        String currency = parts.length == 2 ? parts[1] : "USD";
        return new Contract(null, symbol, "CASH", null, null, null, null, "IDEALPRO", null, currency,
                null, null, null, null, false, null, null);
    }

    public static Contract index(String symbol, String exchange) {
        return new Contract(null, symbol, "IND", null, null, null, null, exchange, null, "USD",
                null, null, null, null, false, null, null);
    }

    public static Contract byConId(int conId) {
        return new Contract(conId, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for callers that prefer named, order-independent construction. */
    public static final class Builder {
        private Integer conId;
        private String symbol;
        private String secType;
        private String lastTradeDateOrContractMonth;
        private Double strike;
        private String right;
        private Double multiplier;
        private String exchange;
        private String primaryExchange;
        private String currency;
        private String localSymbol;
        private String tradingClass;
        private String secIdType;
        private String secId;
        private boolean includeExpired;
        private List<ComboLeg> comboLegs = List.of();
        private DeltaNeutralContract deltaNeutralContract;

        public Builder conId(Integer conId) { this.conId = conId; return this; }
        public Builder symbol(String symbol) { this.symbol = symbol; return this; }
        public Builder secType(String secType) { this.secType = secType; return this; }
        public Builder lastTradeDateOrContractMonth(String value) { this.lastTradeDateOrContractMonth = value; return this; }
        public Builder strike(Double strike) { this.strike = strike; return this; }
        public Builder right(String right) { this.right = right; return this; }
        public Builder multiplier(Double multiplier) { this.multiplier = multiplier; return this; }
        public Builder exchange(String exchange) { this.exchange = exchange; return this; }
        public Builder primaryExchange(String primaryExchange) { this.primaryExchange = primaryExchange; return this; }
        public Builder currency(String currency) { this.currency = currency; return this; }
        public Builder localSymbol(String localSymbol) { this.localSymbol = localSymbol; return this; }
        public Builder tradingClass(String tradingClass) { this.tradingClass = tradingClass; return this; }
        public Builder secIdType(String secIdType) { this.secIdType = secIdType; return this; }
        public Builder secId(String secId) { this.secId = secId; return this; }
        public Builder includeExpired(boolean includeExpired) { this.includeExpired = includeExpired; return this; }
        public Builder comboLegs(List<ComboLeg> comboLegs) { this.comboLegs = comboLegs; return this; }
        public Builder deltaNeutralContract(DeltaNeutralContract value) { this.deltaNeutralContract = value; return this; }

        public Contract build() {
            return new Contract(conId, symbol, secType, lastTradeDateOrContractMonth, strike, right,
                    multiplier, exchange, primaryExchange, currency, localSymbol, tradingClass,
                    secIdType, secId, includeExpired, comboLegs, deltaNeutralContract);
        }
    }

    /** A leg of a combination (BAG) contract. */
    public record ComboLeg(Integer conId, Integer ratio, String action, String exchange,
                           Integer openClose, Integer shortSaleSlot, String designatedLocation,
                           Integer exemptCode) {
    }

    /** Delta-neutral parameters for a combination order. */
    public record DeltaNeutralContract(Integer conId, Double delta, Double price) {
    }
}
