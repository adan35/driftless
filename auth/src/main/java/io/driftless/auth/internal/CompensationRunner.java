package io.driftless.auth.internal;

import io.driftless.auth.internal.config.AuthProperties;
import io.driftless.auth.internal.partner.PartnerClient;
import io.driftless.auth.internal.partner.PartnerUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The bounded-timeout <strong>compensating reversal</strong> — the heart of the "$20K drift fix".
 *
 * <p>When the partner authorize leg does not confirm (timeout / connection drop / 5xx /
 * fail-before-response), the saga must not leave a dangling hold <em>or</em> a dangling partner-side
 * authorization. This runner makes the partner-reverse obligation <strong>durable and retried to
 * completion across sweeps</strong> (review C2/C3):
 *
 * <ol>
 *   <li>{@link AuthorizationFinalizer#beginCompensation} moves the row to {@code COMPENSATING} and
 *       <em>releases the hold immediately</em>, so local available is restored at once — decoupled from
 *       the partner confirmation.
 *   <li>It then resolves the partner reference (local record, else {@code GET /partner/state}) and:
 *       <ul>
 *         <li>if a partner authorization exists (including a <em>late</em> one recorded after the read
 *             timeout), it sends the idempotent partner reverse; on success it finalizes {@code REVERSED};
 *         <li>if the reverse cannot be confirmed yet, it leaves the row {@code COMPENSATING} so the
 *             recovery sweep re-drives it — the obligation can never silently drop;
 *         <li>if the partner shows no side effect <em>and</em> the late-response grace window has
 *             elapsed (nothing more can arrive), it finalizes {@code REVERSED} (provably nothing to cancel).
 *       </ul>
 * </ol>
 *
 * <p>Terminal {@code REVERSED} therefore implies the partner side is confirmed reversed or confirmed to
 * have no side effect — never a guess made while a late partner success is still in flight.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CompensationRunner {

    private final AuthorizationFinalizer finalizer;
    private final PartnerClient partnerClient;
    private final AuthProperties properties;
    private final Clock clock;

    /** Run (or re-drive) the compensating reversal for {@code authId}; idempotent and safe to re-drive. */
    void compensate(UUID authId, String reason) {
        if (!finalizer.beginCompensation(authId)) {
            return;
        }
        settlePartnerObligation(authId, reason);
    }

    /**
     * Settle the partner side of a compensation. The hold is already released; this only decides whether
     * the partner-side authorization can be confirmed reversed (or proven absent) so the row may reach
     * terminal {@code REVERSED} — otherwise it stays {@code COMPENSATING} for the next sweep.
     */
    private void settlePartnerObligation(UUID authId, String reason) {
        Optional<String> partnerRef = finalizer.partnerRefOf(authId).filter(ref -> ref != null && !ref.isBlank());
        if (partnerRef.isEmpty()) {
            partnerRef = partnerClient.discoverPartnerRef(authId.toString());
        }

        if (partnerRef.isPresent()) {
            if (reversePartner(authId, partnerRef.get())) {
                finalizer.finalizeReversed(authId, reason, partnerRef, true);
                log.info("compensation authorization={} finalized REVERSED; partner reverse confirmed", authId);
            } else {
                log.warn(
                        "compensation authorization={} partnerRef={} reverse UNCONFIRMED; staying COMPENSATING (sweep will retry to completion)",
                        authId,
                        partnerRef.get());
            }
            return;
        }

        if (lateResponseGraceElapsed(authId)) {
            // No partner side effect, and the late-response window has closed: provably nothing to cancel.
            finalizer.finalizeReversed(authId, reason, Optional.empty(), true);
            log.info("compensation authorization={} finalized REVERSED; partner confirmed no side effect", authId);
        } else {
            log.info(
                    "compensation authorization={} no partner side effect yet; within grace, staying COMPENSATING",
                    authId);
        }
    }

    /** Bounded-retry idempotent partner reverse. Returns {@code true} only when the partner confirms it. */
    private boolean reversePartner(UUID authId, String partnerRef) {
        int maxAttempts = Math.max(1, properties.getPartner().getCompensationMaxAttempts());
        String reverseRequestId = authId + "|reverse";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                partnerClient.reverse(reverseRequestId, partnerRef);
                return true;
            } catch (PartnerUnavailableException e) {
                log.warn(
                        "compensating reverse attempt {}/{} failed authorization={} partnerRef={}",
                        attempt,
                        maxAttempts,
                        authId,
                        partnerRef);
            }
        }
        return false;
    }

    /**
     * Whether enough time has passed since the authorize request that a <em>late</em> partner response
     * would already have been recorded. Measured from the authorization's creation (when the partner was
     * called), so a timeout-then-late-approve is observed by a later sweep rather than prematurely sealed.
     */
    private boolean lateResponseGraceElapsed(UUID authId) {
        Instant createdAt = finalizer.createdAtOf(authId).orElse(Instant.MIN);
        return !clock.instant().isBefore(createdAt.plus(properties.getRecovery().getPartnerGrace()));
    }
}
