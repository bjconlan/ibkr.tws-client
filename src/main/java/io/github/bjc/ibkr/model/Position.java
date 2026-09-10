package io.github.bjc.ibkr.model;

/** A position held in an account. */
public record Position(
        String account,
        Contract contract,
        String position,
        Double averageCost) {
}
