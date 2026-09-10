package io.github.bjc.ibkr.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Base ids of the inbound messages this client decodes. Values are stable protocol
 * constants from the TWS API; the protobuf wire id is {@code id + 200}.
 */
public enum IncomingId {
    TICK_PRICE(1),
    TICK_SIZE(2),
    ORDER_STATUS(3),
    ERR_MSG(4),
    OPEN_ORDER(5),
    ACCT_VALUE(6),
    PORTFOLIO_VALUE(7),
    ACCT_UPDATE_TIME(8),
    NEXT_VALID_ID(9),
    CONTRACT_DATA(10),
    EXECUTION_DATA(11),
    MANAGED_ACCTS(15),
    HISTORICAL_DATA(17),
    TICK_OPTION_COMPUTATION(21),
    TICK_GENERIC(45),
    TICK_STRING(46),
    CURRENT_TIME(49),
    CONTRACT_DATA_END(52),
    OPEN_ORDER_END(53),
    ACCT_DOWNLOAD_END(54),
    EXECUTION_DATA_END(55),
    TICK_SNAPSHOT_END(57),
    MARKET_DATA_TYPE(58),
    COMMISSION_AND_FEES_REPORT(59),
    POSITION(61),
    POSITION_END(62),
    TICK_REQ_PARAMS(81),
    HISTORICAL_DATA_END(108);

    private final int id;

    private static final Map<Integer, IncomingId> BY_ID =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(IncomingId::id, Function.identity()));

    IncomingId(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static IncomingId fromId(int id) {
        return BY_ID.get(id);
    }
}
