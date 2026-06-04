package io.driftless.rules.api;

/** The terminal outcome of evaluating an {@link AuthContext} against the rule set. */
public enum Decision {
    /** No rule declined the authorization. */
    APPROVE,
    /** A rule declined the authorization; the deciding rule is named in the {@link RuleResult}. */
    DECLINE
}
