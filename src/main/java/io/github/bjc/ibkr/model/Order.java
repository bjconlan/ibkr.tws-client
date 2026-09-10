package io.github.bjc.ibkr.model;

import java.util.Map;

/**
 * A subset of the TWS order message covering the fields used by the common order types.
 * Optional fields are boxed so {@code null} means "not sent"; booleans are primitives and are
 * only transmitted when {@code true}, matching the TWS encoder.
 *
 * <p>Quantities are strings because the TWS protocol transmits them as decimal strings and
 * that is how fractional sizes are preserved.
 */
public record Order(
        int orderId,
        Integer clientId,
        Long permId,
        Integer parentId,
        String action,
        String totalQuantity,
        Integer displaySize,
        String orderType,
        Double lmtPrice,
        Double auxPrice,
        String tif,
        String account,
        boolean allOrNone,
        boolean blockOrder,
        boolean hidden,
        boolean outsideRth,
        boolean sweepToFill,
        Double percentOffset,
        Double trailingPercent,
        Double trailStopPrice,
        Integer minQty,
        String goodAfterTime,
        String goodTillDate,
        String ocaGroup,
        String orderRef,
        String rule80A,
        Integer ocaType,
        Integer triggerMethod,
        String openClose,
        Integer origin,
        String algoStrategy,
        Map<String, String> algoParams,
        String algoId,
        boolean whatIf,
        boolean transmit,
        String modelCode,
        String extOperator,
        boolean notHeld,
        boolean solicited,
        boolean randomizeSize,
        boolean randomizePrice,
        Double discretionaryAmt,
        Double cashQty,
        Integer usePriceMgmtAlgo,
        boolean autoCancelParent,
        boolean postOnly,
        boolean includeOvernight,
        boolean optOutSmartRouting,
        String customerAccount,
        boolean professionalCustomer,
        Integer manualOrderIndicator,
        String submitter) {

    public Order {
        algoParams = algoParams == null ? Map.of() : Map.copyOf(algoParams);
    }

    public static Order market(String action, String totalQuantity) {
        return builder().action(action).totalQuantity(totalQuantity).orderType("MKT").build();
    }

    public static Order limit(String action, String totalQuantity, double price) {
        return builder().action(action).totalQuantity(totalQuantity).orderType("LMT").lmtPrice(price).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; {@code whatIf} and {@code transmit} default to false like the TWS SDK. */
    public static final class Builder {
        private int orderId;
        private Integer clientId;
        private Long permId;
        private Integer parentId;
        private String action;
        private String totalQuantity;
        private Integer displaySize;
        private String orderType;
        private Double lmtPrice;
        private Double auxPrice;
        private String tif = "DAY";
        private String account;
        private boolean allOrNone;
        private boolean blockOrder;
        private boolean hidden;
        private boolean outsideRth;
        private boolean sweepToFill;
        private Double percentOffset;
        private Double trailingPercent;
        private Double trailStopPrice;
        private Integer minQty;
        private String goodAfterTime;
        private String goodTillDate;
        private String ocaGroup;
        private String orderRef;
        private String rule80A;
        private Integer ocaType;
        private Integer triggerMethod;
        private String openClose;
        private Integer origin;
        private String algoStrategy;
        private Map<String, String> algoParams = Map.of();
        private String algoId;
        private boolean whatIf;
        private boolean transmit;
        private String modelCode;
        private String extOperator;
        private boolean notHeld;
        private boolean solicited;
        private boolean randomizeSize;
        private boolean randomizePrice;
        private Double discretionaryAmt;
        private Double cashQty;
        private Integer usePriceMgmtAlgo;
        private boolean autoCancelParent;
        private boolean postOnly;
        private boolean includeOvernight;
        private boolean optOutSmartRouting;
        private String customerAccount;
        private boolean professionalCustomer;
        private Integer manualOrderIndicator;
        private String submitter;

        public Builder orderId(int v) { this.orderId = v; return this; }
        public Builder clientId(Integer v) { this.clientId = v; return this; }
        public Builder permId(Long v) { this.permId = v; return this; }
        public Builder parentId(Integer v) { this.parentId = v; return this; }
        public Builder action(String v) { this.action = v; return this; }
        public Builder totalQuantity(String v) { this.totalQuantity = v; return this; }
        public Builder displaySize(Integer v) { this.displaySize = v; return this; }
        public Builder orderType(String v) { this.orderType = v; return this; }
        public Builder lmtPrice(Double v) { this.lmtPrice = v; return this; }
        public Builder auxPrice(Double v) { this.auxPrice = v; return this; }
        public Builder tif(String v) { this.tif = v; return this; }
        public Builder account(String v) { this.account = v; return this; }
        public Builder allOrNone(boolean v) { this.allOrNone = v; return this; }
        public Builder blockOrder(boolean v) { this.blockOrder = v; return this; }
        public Builder hidden(boolean v) { this.hidden = v; return this; }
        public Builder outsideRth(boolean v) { this.outsideRth = v; return this; }
        public Builder sweepToFill(boolean v) { this.sweepToFill = v; return this; }
        public Builder percentOffset(Double v) { this.percentOffset = v; return this; }
        public Builder trailingPercent(Double v) { this.trailingPercent = v; return this; }
        public Builder trailStopPrice(Double v) { this.trailStopPrice = v; return this; }
        public Builder minQty(Integer v) { this.minQty = v; return this; }
        public Builder goodAfterTime(String v) { this.goodAfterTime = v; return this; }
        public Builder goodTillDate(String v) { this.goodTillDate = v; return this; }
        public Builder ocaGroup(String v) { this.ocaGroup = v; return this; }
        public Builder orderRef(String v) { this.orderRef = v; return this; }
        public Builder rule80A(String v) { this.rule80A = v; return this; }
        public Builder ocaType(Integer v) { this.ocaType = v; return this; }
        public Builder triggerMethod(Integer v) { this.triggerMethod = v; return this; }
        public Builder openClose(String v) { this.openClose = v; return this; }
        public Builder origin(Integer v) { this.origin = v; return this; }
        public Builder algoStrategy(String v) { this.algoStrategy = v; return this; }
        public Builder algoParams(Map<String, String> v) { this.algoParams = v; return this; }
        public Builder algoId(String v) { this.algoId = v; return this; }
        public Builder whatIf(boolean v) { this.whatIf = v; return this; }
        public Builder transmit(boolean v) { this.transmit = v; return this; }
        public Builder modelCode(String v) { this.modelCode = v; return this; }
        public Builder extOperator(String v) { this.extOperator = v; return this; }
        public Builder notHeld(boolean v) { this.notHeld = v; return this; }
        public Builder solicited(boolean v) { this.solicited = v; return this; }
        public Builder randomizeSize(boolean v) { this.randomizeSize = v; return this; }
        public Builder randomizePrice(boolean v) { this.randomizePrice = v; return this; }
        public Builder discretionaryAmt(Double v) { this.discretionaryAmt = v; return this; }
        public Builder cashQty(Double v) { this.cashQty = v; return this; }
        public Builder usePriceMgmtAlgo(Integer v) { this.usePriceMgmtAlgo = v; return this; }
        public Builder autoCancelParent(boolean v) { this.autoCancelParent = v; return this; }
        public Builder postOnly(boolean v) { this.postOnly = v; return this; }
        public Builder includeOvernight(boolean v) { this.includeOvernight = v; return this; }
        public Builder optOutSmartRouting(boolean v) { this.optOutSmartRouting = v; return this; }
        public Builder customerAccount(String v) { this.customerAccount = v; return this; }
        public Builder professionalCustomer(boolean v) { this.professionalCustomer = v; return this; }
        public Builder manualOrderIndicator(Integer v) { this.manualOrderIndicator = v; return this; }
        public Builder submitter(String v) { this.submitter = v; return this; }

        public Order build() {
            return new Order(orderId, clientId, permId, parentId, action, totalQuantity, displaySize,
                    orderType, lmtPrice, auxPrice, tif, account, allOrNone, blockOrder, hidden,
                    outsideRth, sweepToFill, percentOffset, trailingPercent, trailStopPrice, minQty,
                    goodAfterTime, goodTillDate, ocaGroup, orderRef, rule80A, ocaType, triggerMethod,
                    openClose, origin, algoStrategy, algoParams, algoId, whatIf, transmit, modelCode,
                    extOperator, notHeld, solicited, randomizeSize, randomizePrice, discretionaryAmt,
                    cashQty, usePriceMgmtAlgo, autoCancelParent, postOnly, includeOvernight,
                    optOutSmartRouting, customerAccount, professionalCustomer, manualOrderIndicator,
                    submitter);
        }
    }
}
