package io.github.bjc.ibkr.protocol;

import io.github.bjc.ibkr.model.CommissionReport;
import io.github.bjc.ibkr.model.Contract;
import io.github.bjc.ibkr.model.ContractDetails;
import io.github.bjc.ibkr.model.Execution;
import io.github.bjc.ibkr.model.ExecutionFilter;
import io.github.bjc.ibkr.model.Order;
import io.github.bjc.ibkr.model.OrderState;
import io.github.bjc.ibkr.model.Position;
import io.github.bjc.ibkr.proto.CommissionAndFeesReportProto;
import io.github.bjc.ibkr.proto.ComboLegProto;
import io.github.bjc.ibkr.proto.ContractDetailsProto;
import io.github.bjc.ibkr.proto.ContractProto;
import io.github.bjc.ibkr.proto.DeltaNeutralContractProto;
import io.github.bjc.ibkr.proto.ExecutionFilterProto;
import io.github.bjc.ibkr.proto.ExecutionProto;
import io.github.bjc.ibkr.proto.OrderProto;
import io.github.bjc.ibkr.proto.OrderStateProto;
import io.github.bjc.ibkr.proto.PositionProto;

import java.util.List;
import java.util.Map;

/**
 * Conversion between the domain records in {@code io.github.bjc.ibkr.model} and the generated
 * protobuf messages in {@code io.github.bjc.ibkr.proto}.
 *
 * <p>Only the fields carried by the model are set. Both directions are total: missing proto
 * fields become {@code null} (or the record default), and {@code null} model fields are left
 * unset on the wire so TWS applies its own defaults.
 */
public final class ProtoMapper {

    private ProtoMapper() {
    }

    // ---------------------------------------------------------------- outbound

    public static ContractProto.Contract toProto(Contract contract) {
        ContractProto.Contract.Builder b = ContractProto.Contract.newBuilder();
        set(b::setConId, contract.conId());
        setString(contract.symbol(), b::setSymbol);
        setString(contract.secType(), b::setSecType);
        setString(contract.lastTradeDateOrContractMonth(), b::setLastTradeDateOrContractMonth);
        set(b::setStrike, contract.strike());
        setString(contract.right(), b::setRight);
        set(b::setMultiplier, contract.multiplier());
        setString(contract.exchange(), b::setExchange);
        setString(contract.primaryExchange(), b::setPrimaryExch);
        setString(contract.currency(), b::setCurrency);
        setString(contract.localSymbol(), b::setLocalSymbol);
        setString(contract.tradingClass(), b::setTradingClass);
        setString(contract.secIdType(), b::setSecIdType);
        setString(contract.secId(), b::setSecId);
        if (contract.includeExpired()) {
            b.setIncludeExpired(true);
        }
        if (!contract.comboLegs().isEmpty()) {
            b.addAllComboLegs(contract.comboLegs().stream().map(ProtoMapper::toProto).toList());
        }
        if (contract.deltaNeutralContract() != null) {
            Contract.DeltaNeutralContract d = contract.deltaNeutralContract();
            DeltaNeutralContractProto.DeltaNeutralContract.Builder dn = DeltaNeutralContractProto.DeltaNeutralContract.newBuilder();
            set(dn::setConId, d.conId());
            set(dn::setDelta, d.delta());
            set(dn::setPrice, d.price());
            b.setDeltaNeutralContract(dn);
        }
        return b.build();
    }

    public static ComboLegProto.ComboLeg toProto(Contract.ComboLeg leg) {
        ComboLegProto.ComboLeg.Builder b = ComboLegProto.ComboLeg.newBuilder();
        set(b::setConId, leg.conId());
        set(b::setRatio, leg.ratio());
        setString(leg.action(), b::setAction);
        setString(leg.exchange(), b::setExchange);
        set(b::setOpenClose, leg.openClose());
        set(b::setShortSalesSlot, leg.shortSaleSlot());
        setString(leg.designatedLocation(), b::setDesignatedLocation);
        set(b::setExemptCode, leg.exemptCode());
        return b.build();
    }

    public static OrderProto.Order toProto(Order order) {
        OrderProto.Order.Builder b = OrderProto.Order.newBuilder();
        set(b::setClientId, order.clientId());
        if (order.orderId() != 0) b.setOrderId(order.orderId());
        set(b::setPermId, order.permId());
        set(b::setParentId, order.parentId());
        setString(order.action(), b::setAction);
        setString(order.totalQuantity(), b::setTotalQuantity);
        set(b::setDisplaySize, order.displaySize());
        setString(order.orderType(), b::setOrderType);
        set(b::setLmtPrice, order.lmtPrice());
        set(b::setAuxPrice, order.auxPrice());
        setString(order.tif(), b::setTif);
        setString(order.account(), b::setAccount);
        if (order.allOrNone()) b.setAllOrNone(true);
        if (order.blockOrder()) b.setBlockOrder(true);
        if (order.hidden()) b.setHidden(true);
        if (order.outsideRth()) b.setOutsideRth(true);
        if (order.sweepToFill()) b.setSweepToFill(true);
        set(b::setPercentOffset, order.percentOffset());
        set(b::setTrailingPercent, order.trailingPercent());
        set(b::setTrailStopPrice, order.trailStopPrice());
        set(b::setMinQty, order.minQty());
        setString(order.goodAfterTime(), b::setGoodAfterTime);
        setString(order.goodTillDate(), b::setGoodTillDate);
        setString(order.ocaGroup(), b::setOcaGroup);
        setString(order.orderRef(), b::setOrderRef);
        setString(order.rule80A(), b::setRule80A);
        set(b::setOcaType, order.ocaType());
        set(b::setTriggerMethod, order.triggerMethod());
        setString(order.openClose(), b::setOpenClose);
        set(b::setOrigin, order.origin());
        setString(order.algoStrategy(), b::setAlgoStrategy);
        if (!order.algoParams().isEmpty()) b.putAllAlgoParams(order.algoParams());
        setString(order.algoId(), b::setAlgoId);
        if (order.whatIf()) b.setWhatIf(true);
        if (order.transmit()) b.setTransmit(true);
        setString(order.modelCode(), b::setModelCode);
        setString(order.extOperator(), b::setExtOperator);
        if (order.notHeld()) b.setNotHeld(true);
        if (order.solicited()) b.setSolicited(true);
        if (order.randomizeSize()) b.setRandomizeSize(true);
        if (order.randomizePrice()) b.setRandomizePrice(true);
        set(b::setDiscretionaryAmt, order.discretionaryAmt());
        set(b::setCashQty, order.cashQty());
        set(b::setUsePriceMgmtAlgo, order.usePriceMgmtAlgo());
        if (order.autoCancelParent()) b.setAutoCancelParent(true);
        if (order.postOnly()) b.setPostOnly(true);
        if (order.includeOvernight()) b.setIncludeOvernight(true);
        if (order.optOutSmartRouting()) b.setOptOutSmartRouting(true);
        setString(order.customerAccount(), b::setCustomerAccount);
        if (order.professionalCustomer()) b.setProfessionalCustomer(true);
        set(b::setManualOrderIndicator, order.manualOrderIndicator());
        setString(order.submitter(), b::setSubmitter);
        return b.build();
    }

    public static ExecutionFilterProto.ExecutionFilter toProto(ExecutionFilter filter) {
        ExecutionFilterProto.ExecutionFilter.Builder b = ExecutionFilterProto.ExecutionFilter.newBuilder();
        set(b::setClientId, filter.clientId());
        setString(filter.accountCode(), b::setAcctCode);
        setString(filter.time(), b::setTime);
        setString(filter.symbol(), b::setSymbol);
        setString(filter.secType(), b::setSecType);
        setString(filter.exchange(), b::setExchange);
        setString(filter.side(), b::setSide);
        set(b::setLastNDays, filter.lastNDays());
        filter.specificDates().forEach(b::addSpecificDates);
        return b.build();
    }

    // ---------------------------------------------------------------- inbound

    public static Contract fromProto(ContractProto.Contract p) {
        return new Contract(
                p.hasConId() ? p.getConId() : null,
                str(p.hasSymbol(), p::getSymbol),
                str(p.hasSecType(), p::getSecType),
                str(p.hasLastTradeDateOrContractMonth(), p::getLastTradeDateOrContractMonth),
                p.hasStrike() ? p.getStrike() : null,
                str(p.hasRight(), p::getRight),
                p.hasMultiplier() ? p.getMultiplier() : null,
                str(p.hasExchange(), p::getExchange),
                str(p.hasPrimaryExch(), p::getPrimaryExch),
                str(p.hasCurrency(), p::getCurrency),
                str(p.hasLocalSymbol(), p::getLocalSymbol),
                str(p.hasTradingClass(), p::getTradingClass),
                str(p.hasSecIdType(), p::getSecIdType),
                str(p.hasSecId(), p::getSecId),
                p.hasIncludeExpired() && p.getIncludeExpired(),
                p.getComboLegsList().stream().map(ProtoMapper::fromProto).toList(),
                p.hasDeltaNeutralContract() ? fromProto(p.getDeltaNeutralContract()) : null);
    }

    public static Contract.ComboLeg fromProto(ComboLegProto.ComboLeg p) {
        return new Contract.ComboLeg(
                p.hasConId() ? p.getConId() : null,
                p.hasRatio() ? p.getRatio() : null,
                str(p.hasAction(), p::getAction),
                str(p.hasExchange(), p::getExchange),
                p.hasOpenClose() ? p.getOpenClose() : null,
                p.hasShortSalesSlot() ? p.getShortSalesSlot() : null,
                str(p.hasDesignatedLocation(), p::getDesignatedLocation),
                p.hasExemptCode() ? p.getExemptCode() : null);
    }

    public static Contract.DeltaNeutralContract fromProto(DeltaNeutralContractProto.DeltaNeutralContract p) {
        return new Contract.DeltaNeutralContract(
                p.hasConId() ? p.getConId() : null,
                p.hasDelta() ? p.getDelta() : null,
                p.hasPrice() ? p.getPrice() : null);
    }

    public static ContractDetails fromProto(ContractProto.Contract contract, ContractDetailsProto.ContractDetails d) {
        return new ContractDetails(
                fromProto(contract),
                str(d.hasMarketName(), d::getMarketName),
                str(d.hasMinTick(), d::getMinTick),
                str(d.hasOrderTypes(), d::getOrderTypes),
                str(d.hasValidExchanges(), d::getValidExchanges),
                d.hasPriceMagnifier() ? d.getPriceMagnifier() : null,
                d.hasUnderConId() ? d.getUnderConId() : null,
                str(d.hasLongName(), d::getLongName),
                str(d.hasContractMonth(), d::getContractMonth),
                str(d.hasIndustry(), d::getIndustry),
                str(d.hasCategory(), d::getCategory),
                str(d.hasSubcategory(), d::getSubcategory),
                str(d.hasTimeZoneId(), d::getTimeZoneId),
                str(d.hasTradingHours(), d::getTradingHours),
                str(d.hasLiquidHours(), d::getLiquidHours),
                str(d.hasEvRule(), d::getEvRule),
                d.hasEvMultiplier() ? d.getEvMultiplier() : null,
                Map.copyOf(d.getSecIdListMap()),
                d.hasAggGroup() ? d.getAggGroup() : null,
                str(d.hasUnderSymbol(), d::getUnderSymbol),
                str(d.hasUnderSecType(), d::getUnderSecType),
                str(d.hasMarketRuleIds(), d::getMarketRuleIds),
                str(d.hasRealExpirationDate(), d::getRealExpirationDate),
                str(d.hasStockType(), d::getStockType),
                str(d.hasCusip(), d::getCusip),
                str(d.hasBondType(), d::getBondType),
                str(d.hasDescAppend(), d::getDescAppend),
                str(d.hasBondNotes(), d::getBondNotes),
                str(d.hasSettlementMethod(), d::getSettlementMethod));
    }

    public static Order fromProto(OrderProto.Order p) {
        return Order.builder()
                .clientId(p.hasClientId() ? p.getClientId() : null)
                .orderId(p.hasOrderId() ? p.getOrderId() : 0)
                .permId(p.hasPermId() ? p.getPermId() : null)
                .parentId(p.hasParentId() ? p.getParentId() : null)
                .action(str(p.hasAction(), p::getAction))
                .totalQuantity(str(p.hasTotalQuantity(), p::getTotalQuantity))
                .displaySize(p.hasDisplaySize() ? p.getDisplaySize() : null)
                .orderType(str(p.hasOrderType(), p::getOrderType))
                .lmtPrice(p.hasLmtPrice() ? p.getLmtPrice() : null)
                .auxPrice(p.hasAuxPrice() ? p.getAuxPrice() : null)
                .tif(str(p.hasTif(), p::getTif))
                .account(str(p.hasAccount(), p::getAccount))
                .allOrNone(p.hasAllOrNone() && p.getAllOrNone())
                .blockOrder(p.hasBlockOrder() && p.getBlockOrder())
                .hidden(p.hasHidden() && p.getHidden())
                .outsideRth(p.hasOutsideRth() && p.getOutsideRth())
                .sweepToFill(p.hasSweepToFill() && p.getSweepToFill())
                .percentOffset(p.hasPercentOffset() ? p.getPercentOffset() : null)
                .trailingPercent(p.hasTrailingPercent() ? p.getTrailingPercent() : null)
                .trailStopPrice(p.hasTrailStopPrice() ? p.getTrailStopPrice() : null)
                .minQty(p.hasMinQty() ? p.getMinQty() : null)
                .goodAfterTime(str(p.hasGoodAfterTime(), p::getGoodAfterTime))
                .goodTillDate(str(p.hasGoodTillDate(), p::getGoodTillDate))
                .ocaGroup(str(p.hasOcaGroup(), p::getOcaGroup))
                .orderRef(str(p.hasOrderRef(), p::getOrderRef))
                .rule80A(str(p.hasRule80A(), p::getRule80A))
                .ocaType(p.hasOcaType() ? p.getOcaType() : null)
                .triggerMethod(p.hasTriggerMethod() ? p.getTriggerMethod() : null)
                .openClose(str(p.hasOpenClose(), p::getOpenClose))
                .origin(p.hasOrigin() ? p.getOrigin() : null)
                .algoStrategy(str(p.hasAlgoStrategy(), p::getAlgoStrategy))
                .algoParams(Map.copyOf(p.getAlgoParamsMap()))
                .algoId(str(p.hasAlgoId(), p::getAlgoId))
                .whatIf(p.hasWhatIf() && p.getWhatIf())
                .transmit(p.hasTransmit() && p.getTransmit())
                .modelCode(str(p.hasModelCode(), p::getModelCode))
                .extOperator(str(p.hasExtOperator(), p::getExtOperator))
                .notHeld(p.hasNotHeld() && p.getNotHeld())
                .solicited(p.hasSolicited() && p.getSolicited())
                .randomizeSize(p.hasRandomizeSize() && p.getRandomizeSize())
                .randomizePrice(p.hasRandomizePrice() && p.getRandomizePrice())
                .discretionaryAmt(p.hasDiscretionaryAmt() ? p.getDiscretionaryAmt() : null)
                .cashQty(p.hasCashQty() ? p.getCashQty() : null)
                .usePriceMgmtAlgo(p.hasUsePriceMgmtAlgo() ? p.getUsePriceMgmtAlgo() : null)
                .autoCancelParent(p.hasAutoCancelParent() && p.getAutoCancelParent())
                .postOnly(p.hasPostOnly() && p.getPostOnly())
                .includeOvernight(p.hasIncludeOvernight() && p.getIncludeOvernight())
                .optOutSmartRouting(p.hasOptOutSmartRouting() && p.getOptOutSmartRouting())
                .customerAccount(str(p.hasCustomerAccount(), p::getCustomerAccount))
                .professionalCustomer(p.hasProfessionalCustomer() && p.getProfessionalCustomer())
                .manualOrderIndicator(p.hasManualOrderIndicator() ? p.getManualOrderIndicator() : null)
                .submitter(str(p.hasSubmitter(), p::getSubmitter))
                .build();
    }

    public static OrderState fromProto(OrderStateProto.OrderState p) {
        return new OrderState(
                str(p.hasStatus(), p::getStatus),
                p.hasInitMarginBefore() ? p.getInitMarginBefore() : null,
                p.hasMaintMarginBefore() ? p.getMaintMarginBefore() : null,
                p.hasEquityWithLoanBefore() ? p.getEquityWithLoanBefore() : null,
                p.hasInitMarginChange() ? p.getInitMarginChange() : null,
                p.hasMaintMarginChange() ? p.getMaintMarginChange() : null,
                p.hasEquityWithLoanChange() ? p.getEquityWithLoanChange() : null,
                p.hasInitMarginAfter() ? p.getInitMarginAfter() : null,
                p.hasMaintMarginAfter() ? p.getMaintMarginAfter() : null,
                p.hasEquityWithLoanAfter() ? p.getEquityWithLoanAfter() : null,
                p.hasCommissionAndFees() ? p.getCommissionAndFees() : null,
                p.hasMinCommissionAndFees() ? p.getMinCommissionAndFees() : null,
                p.hasMaxCommissionAndFees() ? p.getMaxCommissionAndFees() : null,
                str(p.hasCommissionAndFeesCurrency(), p::getCommissionAndFeesCurrency),
                str(p.hasMarginCurrency(), p::getMarginCurrency),
                str(p.hasWarningText(), p::getWarningText),
                str(p.hasCompletedTime(), p::getCompletedTime),
                str(p.hasCompletedStatus(), p::getCompletedStatus));
    }

    public static Execution fromProto(ExecutionProto.Execution p) {
        return new Execution(
                p.hasOrderId() ? p.getOrderId() : 0,
                p.hasClientId() ? p.getClientId() : null,
                str(p.hasExecId(), p::getExecId),
                str(p.hasTime(), p::getTime),
                str(p.hasAcctNumber(), p::getAcctNumber),
                str(p.hasExchange(), p::getExchange),
                str(p.hasSide(), p::getSide),
                str(p.hasShares(), p::getShares),
                p.hasPrice() ? p.getPrice() : null,
                p.hasPermId() ? p.getPermId() : null,
                p.hasIsLiquidation() && p.getIsLiquidation(),
                str(p.hasCumQty(), p::getCumQty),
                p.hasAvgPrice() ? p.getAvgPrice() : null,
                str(p.hasOrderRef(), p::getOrderRef),
                str(p.hasModelCode(), p::getModelCode),
                p.hasLastLiquidity() ? p.getLastLiquidity() : null,
                str(p.hasSubmitter(), p::getSubmitter));
    }

    public static Position fromProto(PositionProto.Position p) {
        return new Position(
                str(p.hasAccount(), p::getAccount),
                p.hasContract() ? fromProto(p.getContract()) : null,
                str(p.hasPosition(), p::getPosition),
                p.hasAvgCost() ? p.getAvgCost() : null);
    }

    public static CommissionReport fromProto(CommissionAndFeesReportProto.CommissionAndFeesReport p) {
        return new CommissionReport(
                str(p.hasExecId(), p::getExecId),
                p.hasCommissionAndFees() ? p.getCommissionAndFees() : 0.0,
                str(p.hasCurrency(), p::getCurrency),
                p.hasRealizedPNL() ? p.getRealizedPNL() : null,
                p.hasBondYield() ? p.getBondYield() : null,
                p.hasYieldRedemptionDate() ? Integer.parseInt(p.getYieldRedemptionDate()) : null);
    }

    // ---------------------------------------------------------------- helpers

    private static String str(boolean present, java.util.function.Supplier<String> getter) {
        return present ? getter.get() : null;
    }

    private static <T> void set(java.util.function.Consumer<T> setter, T value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static void setString(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isEmpty()) {
            setter.accept(value);
        }
    }

    /** Combo legs are ordered; keep the list type available for callers. */
    public static List<Contract.ComboLeg> comboLegs(Contract contract) {
        return contract.comboLegs();
    }
}
