package io.github.bjc.ibkr.model;

/** Order state as reported in open order messages. */
public record OrderState(
        String status,
        Double initMarginBefore,
        Double maintMarginBefore,
        Double equityWithLoanBefore,
        Double initMarginChange,
        Double maintMarginChange,
        Double equityWithLoanChange,
        Double initMarginAfter,
        Double maintMarginAfter,
        Double equityWithLoanAfter,
        Double commissionAndFees,
        Double minCommissionAndFees,
        Double maxCommissionAndFees,
        String commissionAndFeesCurrency,
        String marginCurrency,
        String warningText,
        String completedTime,
        String completedStatus) {
}
