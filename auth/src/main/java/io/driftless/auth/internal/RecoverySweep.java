package io.driftless.auth.internal;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.internal.config.AuthProperties;
import io.driftless.auth.internal.partner.PartnerAuthOutcome;
import io.driftless.auth.internal.partner.PartnerClient;
import io.driftless.auth.internal.persistence.AuthorizationEntity;
import io.driftless.auth.internal.persistence.AuthorizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The crash-recovery sweep: what makes "process killed at any step boundary recovers cleanly" true.
 *
 * <p>Because {@code authorize} commits the hold + authorization <em>before</em> the partner call, a
 * process killed mid-saga leaves an authorization stuck in an in-flight state ({@code AUTHORIZING}
 * with no finalize, or {@code COMPENSATING} with the reversal unfinished). This sweep periodically
 * (and on demand, via {@link #sweep()}) adopts those rows once they are older than {@code
 * auth.recovery.stuck-after} and drives them to a terminal state:
 *
 * <ul>
 *   <li>{@code AUTHORIZING} — ask the partner what it actually did ({@code GET /partner/state}). If it
 *       approved, finalize {@code AUTHORIZED}; if it declined, finalize {@code DECLINED}; if it never
 *       confirmed (unknown, or a side-effect-only fail-before-response), compensate to {@code
 *       REVERSED} so no hold dangles.
 *   <li>{@code COMPENSATING} — re-drive the (idempotent) compensating reversal: re-probe {@code
 *       GET /partner/state}, send the idempotent partner reverse if the partner shows an
 *       approved/active authorization (including a <em>late</em> one), and finalize {@code REVERSED}
 *       only once the partner side is confirmed reversed or confirmed to have no side effect. Until
 *       then the row stays {@code COMPENSATING} and is retried on the next sweep — the partner-reverse
 *       obligation is retried to completion and cannot silently drop (review C2/C3).
 * </ul>
 *
 * <p>Each authorization is handled in isolation: one failure is logged and the sweep continues, so a
 * single stuck partner cannot stall recovery of the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoverySweep {

    private static final String REASON_RECOVERY = "RECOVERY_COMPENSATION";

    private final AuthorizationRepository authorizations;
    private final AuthorizationFinalizer finalizer;
    private final CompensationRunner compensation;
    private final PartnerClient partnerClient;
    private final AuthProperties properties;
    private final Clock clock;

    /** Scheduled entry point; also callable directly (tests, ops) to force a sweep now. */
    @Scheduled(fixedDelayString = "${auth.recovery.poll-delay:PT10S}")
    public void sweep() {
        Instant threshold = clock.instant().minus(properties.getRecovery().getStuckAfter());

        List<AuthorizationEntity> stuckAuthorizing =
                authorizations.findByStatusAndUpdatedAtBefore(AuthorizationStatus.AUTHORIZING, threshold);
        for (AuthorizationEntity auth : stuckAuthorizing) {
            recoverAuthorizing(auth.getId());
        }

        List<AuthorizationEntity> stuckCompensating =
                authorizations.findByStatusAndUpdatedAtBefore(AuthorizationStatus.COMPENSATING, threshold);
        for (AuthorizationEntity auth : stuckCompensating) {
            reCompensate(auth.getId());
        }

        int total = stuckAuthorizing.size() + stuckCompensating.size();
        if (total > 0) {
            log.info(
                    "recovery sweep drove {} authorizing + {} compensating authorizations",
                    stuckAuthorizing.size(),
                    stuckCompensating.size());
        }
    }

    private void recoverAuthorizing(UUID authId) {
        try {
            PartnerAuthOutcome outcome = partnerClient.recoverOutcome(authId.toString());
            switch (outcome.kind()) {
                case APPROVED -> finalizer.markAuthorized(
                        authId, outcome.partnerRef().orElseThrow());
                case DECLINED -> finalizer.declineByPartner(
                        authId, outcome.reason().orElse("PARTNER_DECLINED"));
                case NO_RESPONSE -> compensation.compensate(authId, REASON_RECOVERY);
            }
            log.info("recovery drove authorizing authorization={} via partner outcome {}", authId, outcome.kind());
        } catch (RuntimeException e) {
            log.warn(
                    "recovery of authorizing authorization={} failed; will retry next sweep ({})",
                    authId,
                    e.toString());
        }
    }

    private void reCompensate(UUID authId) {
        try {
            compensation.compensate(authId, REASON_RECOVERY);
        } catch (RuntimeException e) {
            log.warn(
                    "recovery of compensating authorization={} failed; will retry next sweep ({})",
                    authId,
                    e.toString());
        }
    }
}
