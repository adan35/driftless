package io.driftless.auth.internal;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.internal.event.AuthorizationLifecycleEvent;
import io.driftless.auth.internal.persistence.AuthorizationEntity;
import io.driftless.auth.internal.persistence.AuthorizationRepository;
import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.rules.velocity.VelocityStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional state writers for the authorize leg's <em>second phase</em> (after the bounded
 * partner call) and for compensation. Each is its own {@code @Transactional} unit so the hold +
 * authorization were already committed before the network call (crash-safety), and each finalize is a
 * separate, idempotent transaction that the in-line saga and the recovery sweep both drive through.
 *
 * <p>Every method loads the authorization {@code FOR UPDATE} and re-checks the live status, so a
 * concurrent finalize (e.g. the in-line path racing the recovery sweep) acts at most once: the loser
 * sees a state that no longer permits the transition and no-ops. This is the inside-transaction
 * re-validation that makes "process killed at any step boundary recovers cleanly" true.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AuthorizationFinalizer {

    private final AuthorizationRepository authorizations;
    private final Holds holds;
    private final LifecycleOutbox events;
    private final VelocityStore velocityStore;
    private final Clock clock;

    /**
     * Operator signal (review W3 / QA #3): the number of times a compensation reached terminal {@code
     * REVERSED} while a partner reference was known but its reverse was <em>not</em> confirmed — i.e. a
     * partner-side authorization could be dangling. After the C2/C3 fix this is never incremented (a
     * compensation stays {@code COMPENSATING} until the partner side is confirmed reversed or confirmed
     * to have no side effect), so it reads zero; a non-zero value is a regression an operator can alert on.
     */
    private final AtomicLong danglingPartnerReverseFinalizations = new AtomicLong();

    /** Partner approved: AUTHORIZING → AUTHORIZED, recording the partner reference and emitting. */
    @Transactional
    void markAuthorized(UUID authId, String partnerRef) {
        AuthorizationEntity auth = load(authId);
        if (auth.getStatus() != AuthorizationStatus.AUTHORIZING) {
            log.info("markAuthorized no-op authorization={} already {}", authId, auth.getStatus());
            return;
        }
        Instant now = clock.instant();
        auth.recordPartnerRef(partnerRef);
        auth.transitionTo(AuthorizationStatus.AUTHORIZED, now);
        authorizations.save(auth);
        // Velocity is counted only on a CONFIRMED authorization (review W2): a declined or compensated
        // attempt must not count toward the customer's limit. Idempotent on the authorization id, so the
        // in-line finalize and a recovery re-drive record it exactly once.
        velocityStore.record(
                authId.toString(),
                AccountId.of(auth.getAccountId()),
                Money.of(auth.getAmountMinor(), Currency.getInstance(auth.getCurrency())),
                now);
        events.emit(auth, AuthorizationLifecycleEvent.AUTHORIZED, null, now);
        log.info("authorization {} AUTHORIZED partnerRef={}", authId, partnerRef);
    }

    /** Partner declined: AUTHORIZING → DECLINED, releasing the hold (no money moved). */
    @Transactional
    void declineByPartner(UUID authId, String reason) {
        AuthorizationEntity auth = load(authId);
        if (auth.getStatus() != AuthorizationStatus.AUTHORIZING) {
            log.info("declineByPartner no-op authorization={} already {}", authId, auth.getStatus());
            return;
        }
        Instant now = clock.instant();
        holds.releaseActive(authId, now);
        auth.recordDeclineReason(reason);
        auth.transitionTo(AuthorizationStatus.DECLINED, now);
        authorizations.save(auth);
        events.emit(auth, AuthorizationLifecycleEvent.DECLINED, reason, now);
        log.info("authorization {} DECLINED by partner reason={}", authId, reason);
    }

    /**
     * Move an in-flight authorization into {@link AuthorizationStatus#COMPENSATING} and <em>release the
     * hold immediately</em> — decoupling local available-balance recovery from the partner reverse (C2/C3).
     * The row stays {@code COMPENSATING} (not terminal) until the partner side is confirmed reversed or
     * confirmed to have no side effect, so the recovery sweep keeps re-driving the partner obligation and
     * it can never silently drop. Returns {@code true} if the authorization is (now or already)
     * compensating, {@code false} if it has already reached a terminal state and needs no compensation.
     */
    @Transactional
    boolean beginCompensation(UUID authId) {
        AuthorizationEntity auth = load(authId);
        AuthorizationStatus status = auth.getStatus();
        if (status == AuthorizationStatus.COMPENSATING) {
            holds.releaseActive(authId, clock.instant()); // idempotent: keeps available restored across re-drives
            return true;
        }
        if (status != AuthorizationStatus.AUTHORIZING) {
            log.info("beginCompensation skip authorization={} already terminal {}", authId, status);
            return false;
        }
        Instant now = clock.instant();
        holds.releaseActive(authId, now);
        auth.transitionTo(AuthorizationStatus.COMPENSATING, now);
        authorizations.save(auth);
        log.info("authorization {} COMPENSATING; hold released, partner reverse pending", authId);
        return true;
    }

    /**
     * Finish the compensating reversal: record any discovered partner reference and drive to {@link
     * AuthorizationStatus#REVERSED}. The hold was already released by {@link #beginCompensation}; this
     * call is the terminal transition and must only run once the partner side is settled — either {@code
     * partnerReverseConfirmed} (reversed) or with no {@code discoveredPartnerRef} (no side effect to
     * cancel). Idempotent: a re-drive after the terminal state is reached is a no-op.
     */
    @Transactional
    void finalizeReversed(
            UUID authId, String reason, Optional<String> discoveredPartnerRef, boolean partnerReverseConfirmed) {
        AuthorizationEntity auth = load(authId);
        AuthorizationStatus status = auth.getStatus();
        if (status == AuthorizationStatus.REVERSED) {
            return;
        }
        if (status != AuthorizationStatus.COMPENSATING
                && status != AuthorizationStatus.AUTHORIZING
                && status != AuthorizationStatus.AUTHORIZED) {
            log.info("finalizeReversed skip authorization={} state {}", authId, status);
            return;
        }
        Instant now = clock.instant();
        discoveredPartnerRef.ifPresent(auth::recordPartnerRef);
        holds.releaseActive(authId, now);
        auth.recordDeclineReason(reason);
        auth.transitionTo(AuthorizationStatus.REVERSED, now);
        authorizations.save(auth);
        events.emit(auth, AuthorizationLifecycleEvent.REVERSED, reason, now);
        if (auth.getPartnerRef() != null && !partnerReverseConfirmed) {
            // Defensive guard: a terminal REVERSED with a known partner ref but no confirmed reverse means
            // a partner-side authorization may dangle. The C2/C3 flow never reaches here; if it ever does,
            // surface it loudly and on the operator counter rather than letting it pass silently.
            danglingPartnerReverseFinalizations.incrementAndGet();
            log.warn(
                    "DANGLING_PARTNER_REVERSE authorization={} finalized REVERSED with partnerRef={} but no confirmed reverse",
                    authId,
                    auth.getPartnerRef());
        }
        log.info("authorization {} REVERSED reason={}", authId, reason);
    }

    /** Current partner reference for an authorization, when one is known. */
    @Transactional(readOnly = true)
    Optional<String> partnerRefOf(UUID authId) {
        return authorizations.findById(authId).map(AuthorizationEntity::getPartnerRef);
    }

    /** When the authorize request was created — the clock from which the late-partner grace window runs. */
    @Transactional(readOnly = true)
    Optional<Instant> createdAtOf(UUID authId) {
        return authorizations.findById(authId).map(AuthorizationEntity::getCreatedAt);
    }

    /** Operator signal: compensations finalized REVERSED without a confirmed partner reverse (must be 0). */
    long danglingPartnerReverseFinalizations() {
        return danglingPartnerReverseFinalizations.get();
    }

    /** Operator signal: authorizations still {@code COMPENSATING} — outstanding partner-reverse obligations. */
    @Transactional(readOnly = true)
    long outstandingPartnerObligations() {
        return authorizations.countByStatus(AuthorizationStatus.COMPENSATING);
    }

    private AuthorizationEntity load(UUID authId) {
        return authorizations
                .findByIdForUpdate(authId)
                .orElseThrow(() -> new IllegalStateException("authorization disappeared mid-saga: " + authId));
    }
}
