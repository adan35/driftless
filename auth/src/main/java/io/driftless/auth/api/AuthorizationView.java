package io.driftless.auth.api;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable read model of an authorization's full state, returned by the read endpoint and as the
 * result of the mutating saga operations. Never a JPA entity — the web layer and callers see only
 * this projection.
 *
 * @param id the authorization id
 * @param account the account the authorization is drawn against
 * @param amount the requested amount (minor units)
 * @param mcc merchant category code
 * @param merchantId opaque merchant identifier
 * @param tokenId the gating token, when one was supplied
 * @param status the current saga state
 * @param partnerRef the partner-assigned reference, once the partner has responded
 * @param declineReason the machine-readable decline reason, when declined
 * @param createdAt business time the authorization was created
 * @param updatedAt business time of the last state change
 */
public record AuthorizationView(
        UUID id,
        AccountId account,
        Money amount,
        String mcc,
        String merchantId,
        Optional<UUID> tokenId,
        AuthorizationStatus status,
        Optional<String> partnerRef,
        Optional<String> declineReason,
        Instant createdAt,
        Instant updatedAt) {}
