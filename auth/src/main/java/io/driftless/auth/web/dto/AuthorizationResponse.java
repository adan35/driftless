package io.driftless.auth.web.dto;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.AuthorizationView;
import java.time.Instant;
import java.util.UUID;

/**
 * Full read projection for {@code GET /authorizations/{id}}. Never a JPA entity — built from the
 * {@link AuthorizationView} read model; absent fields are serialized as {@code null}.
 *
 * @param id the authorization id
 * @param account the account id
 * @param amountMinor the amount in minor units
 * @param currency ISO-4217 currency code
 * @param mcc merchant category code
 * @param merchantId opaque merchant identifier
 * @param tokenId the gating token id, or {@code null}
 * @param status the current saga state
 * @param partnerRef the partner reference, or {@code null}
 * @param declineReason the decline/reversal reason, or {@code null}
 * @param createdAt business time created
 * @param updatedAt business time of the last state change
 */
public record AuthorizationResponse(
        UUID id,
        UUID account,
        long amountMinor,
        String currency,
        String mcc,
        String merchantId,
        UUID tokenId,
        AuthorizationStatus status,
        String partnerRef,
        String declineReason,
        Instant createdAt,
        Instant updatedAt) {

    public static AuthorizationResponse from(AuthorizationView view) {
        return new AuthorizationResponse(
                view.id(),
                view.account().value(),
                view.amount().amountMinor(),
                view.amount().currency().getCurrencyCode(),
                view.mcc(),
                view.merchantId(),
                view.tokenId().orElse(null),
                view.status(),
                view.partnerRef().orElse(null),
                view.declineReason().orElse(null),
                view.createdAt(),
                view.updatedAt());
    }
}
