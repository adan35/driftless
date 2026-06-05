package io.driftless.auth.internal.partner;

import java.util.Optional;

/**
 * The outcome of the partner authorize leg, normalized for the saga.
 *
 * <p>The partner can answer in three decisive ways the saga must branch on:
 *
 * <ul>
 *   <li>{@link Kind#APPROVED} — carries the partner reference; drive to {@code AUTHORIZED}.
 *   <li>{@link Kind#DECLINED} — carries the decline reason; release the hold, drive to {@code
 *       DECLINED}.
 *   <li>{@link Kind#NO_RESPONSE} — timeout / connection drop / 5xx / fail-before-response; the saga
 *       did not learn the result, so it must run the bounded-timeout <em>compensating reversal</em>.
 * </ul>
 *
 * @param kind the decisive outcome
 * @param partnerRef the partner reference when {@link Kind#APPROVED}, else empty
 * @param reason the decline reason when {@link Kind#DECLINED}, else empty
 */
public record PartnerAuthOutcome(Kind kind, Optional<String> partnerRef, Optional<String> reason) {

    /** The three decisive shapes of a partner authorize response. */
    public enum Kind {
        APPROVED,
        DECLINED,
        NO_RESPONSE
    }

    public static PartnerAuthOutcome approved(String partnerRef) {
        return new PartnerAuthOutcome(Kind.APPROVED, Optional.of(partnerRef), Optional.empty());
    }

    public static PartnerAuthOutcome declined(String reason) {
        return new PartnerAuthOutcome(Kind.DECLINED, Optional.empty(), Optional.ofNullable(reason));
    }

    public static PartnerAuthOutcome noResponse() {
        return new PartnerAuthOutcome(Kind.NO_RESPONSE, Optional.empty(), Optional.empty());
    }
}
