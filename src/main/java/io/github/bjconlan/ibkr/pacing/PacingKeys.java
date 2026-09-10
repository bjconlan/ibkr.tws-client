package io.github.bjconlan.ibkr.pacing;

/**
 * The fingerprints a request offers for {@link PacingRule.KeyedRate} rules. A rule selects the
 * key it needs through {@link PacingRule.KeyedRate.Scope}; unused keys may be {@code null}.
 *
 * @param request the full request identity (used for "identical request" rules)
 * @param scope   the contract/exchange/tick-type identity (used for "same instrument" rules)
 */
public record PacingKeys(String request, String scope) {

    /** No keys; rules that need one are skipped. */
    public static final PacingKeys NONE = new PacingKeys(null, null);
}
