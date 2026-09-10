package io.github.bjc.ibkr.model;

/**
 * Decoded tick attributes. TWS sends these as a bit mask; the accessors below name the bits
 * that are meaningful for price and size ticks.
 */
public record TickAttrib(boolean canAutoExecute, boolean pastLimit, boolean preOpen) {

    public static TickAttrib fromMask(int mask) {
        return new TickAttrib(
                (mask & 0x01) != 0,
                (mask & 0x02) != 0,
                (mask & 0x04) != 0);
    }
}
