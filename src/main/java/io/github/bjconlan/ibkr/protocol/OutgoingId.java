package io.github.bjconlan.ibkr.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Base ids of the outbound requests this client supports. Values are stable protocol
 * constants from the TWS API; the protobuf wire id is {@code id + 200}.
 */
public enum OutgoingId {
    REQ_MKT_DATA(1),
    CANCEL_MKT_DATA(2),
    PLACE_ORDER(3),
    CANCEL_ORDER(4),
    REQ_OPEN_ORDERS(5),
    REQ_ACCOUNT_DATA(6),
    REQ_EXECUTIONS(7),
    REQ_IDS(8),
    REQ_CONTRACT_DATA(9),
    REQ_HISTORICAL_DATA(20),
    CANCEL_HISTORICAL_DATA(25),
    REQ_CURRENT_TIME(49),
    REQ_MARKET_DATA_TYPE(59),
    REQ_POSITIONS(61),
    CANCEL_POSITIONS(64),
    START_API(71),
    CANCEL_CONTRACT_DATA(106);

    private final int id;

    private static final Map<Integer, OutgoingId> BY_ID =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(OutgoingId::id, Function.identity()));

    OutgoingId(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static OutgoingId fromId(int id) {
        return BY_ID.get(id);
    }
}
