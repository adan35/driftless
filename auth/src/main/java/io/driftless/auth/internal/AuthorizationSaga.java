package io.driftless.auth.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.auth.api.AuthorizationNotFound;
import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.AuthorizationView;
import io.driftless.auth.api.IllegalAuthorizationState;
import io.driftless.auth.internal.event.AuthorizationLifecycleEvent;
import io.driftless.auth.internal.partner.PartnerAuthOutcome;
import io.driftless.auth.internal.partner.PartnerClient;
import io.driftless.auth.internal.partner.PartnerUnavailableException;
import io.driftless.auth.internal.persistence.AuthPreview;
import io.driftless.auth.internal.persistence.AuthorizationEntity;
import io.driftless.auth.internal.persistence.AuthorizationRepository;
import io.driftless.common.id.AccountId;
import io.driftless.common.id.TokenId;
import io.driftless.common.money.Money;
import io.driftless.idempotency.api.IdempotencyGuard;
import io.driftless.idempotency.api.IdempotentResult;
import io.driftless.idempotency.api.StoredResult;
import io.driftless.ledger.api.Balance;
import io.driftless.ledger.api.Ledger;
import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.Decision;
import io.driftless.rules.api.RuleEngine;
import io.driftless.rules.api.RuleResult;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.velocity.VelocityStore;
import io.driftless.tokens.api.Token;
import io.driftless.tokens.api.TokenService;
import io.driftless.tokens.api.TokenStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Spec 03 authorization saga: the engine that "keeps money correct under failure".
 *
 * <p>It runs the full <strong>authorize → capture → reversal</strong> lifecycle, maintains the
 * available-vs-posted distinction (a hold reduces available via the {@code HoldView}; only capture /
 * reversal move posted), and on a partner timeout/failure runs a bounded-timeout compensating
 * reversal so a stuck downstream call never leaves a phantom hold.
 *
 * <p><strong>Transaction boundaries (crash-safety).</strong> {@code authorize} is two phases:
 *
 * <ol>
 *   <li><em>Phase 1</em> (inside the idempotency guard's single transaction): evaluate rules, place
 *       the hold, persist the authorization in {@code AUTHORIZING}, record velocity — then commit.
 *       The hold + authorization are durable <em>before</em> the network call, so the process can die
 *       at any boundary and recover.
 *   <li><em>Phase 2</em> (after the guard returns, only on a fresh execution): the bounded partner
 *       call, then a separate transactional finalize ({@link AuthorizationFinalizer}) to {@code
 *       AUTHORIZED}/{@code DECLINED}, or the {@link CompensationRunner} to {@code REVERSED}.
 * </ol>
 *
 * A replay of {@code authorize} skips phase 2 entirely and returns the current persisted state; a
 * crash between the phases is healed by the recovery sweep. {@code capture} and {@code reverse} are
 * single guarded transactions (the partner call there is short and idempotent, and a failure rolls
 * the whole step back to be retried).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationSaga implements io.driftless.auth.spi.SagaMetricsView {

    private static final String AUTHORIZE_SCOPE = "auth.authorize:";
    private static final String CAPTURE_SCOPE = "auth.capture:";
    private static final String REVERSE_SCOPE = "auth.reverse:";
    private static final String REASON_TOKEN_INACTIVE = "TOKEN_INACTIVE";
    private static final String REASON_PARTNER_DECLINED = "PARTNER_DECLINED";
    private static final String REASON_PARTNER_TIMEOUT = "PARTNER_TIMEOUT";
    private static final String REASON_REVERSED = "REVERSED";

    private final IdempotencyGuard guard;
    private final ObjectMapper objectMapper;
    private final Ledger ledger;
    private final RuleEngine ruleEngine;
    private final VelocityStore velocityStore;
    private final TokenService tokenService;
    private final AuthorizationRepository authorizations;
    private final Holds holds;
    private final LifecycleOutbox events;
    private final SettlementPoster settlement;
    private final AuthorizationFinalizer finalizer;
    private final CompensationRunner compensation;
    private final PartnerClient partnerClient;
    private final Clock clock;

    // --- authorize -------------------------------------------------------------------------------

    /**
     * Run the authorize leg under the caller's idempotency key: evaluate rules, place a hold, then
     * call the partner under a bounded timeout and finalize (or compensate). Returns the resulting
     * authorization state.
     */
    public AuthorizationView authorize(AuthorizeCommand cmd, String idempotencyKey) {
        String key = AUTHORIZE_SCOPE + requireKey(idempotencyKey);
        String requestHash = hash("AUTHORIZE|%s|%d|%s|%s|%s|%s"
                .formatted(
                        cmd.account(),
                        cmd.amount().amountMinor(),
                        cmd.amount().currency().getCurrencyCode(),
                        cmd.mcc(),
                        cmd.merchantId(),
                        cmd.tokenId().map(TokenId::toString).orElse("")));

        IdempotentResult<UUID> result =
                guard.execute(key, requestHash, () -> stored(authorizePhase1(cmd, idempotencyKey)));
        UUID authId = result.value();

        if (!result.replayed()) {
            runPartnerAuthorizeLeg(authId);
        }
        return view(authId);
    }

    /** Phase 1, inside the guard transaction: gate, evaluate, place hold (or decline). Returns the id. */
    private UUID authorizePhase1(AuthorizeCommand cmd, String idempotencyKey) {
        Instant now = clock.instant();
        UUID authId = UUID.randomUUID();
        Currency currency = cmd.amount().currency();

        String cardRef = "acct:" + cmd.account();
        if (cmd.tokenId().isPresent()) {
            Token token = tokenService.find(cmd.tokenId().get());
            cardRef = token.cardRef();
            if (token.status() != TokenStatus.ACTIVE) {
                AuthorizationEntity declined =
                        persistDeclined(authId, cmd, cardRef, REASON_TOKEN_INACTIVE, idempotencyKey, now);
                log.info("authorization {} DECLINED token {} status {}", authId, token.id(), token.status());
                return declined.getId();
            }
        }

        Money available = ledger.balanceOf(cmd.account()).available();
        VelocitySnapshot velocity = velocityStore.snapshot(cmd.account(), currency, now);
        AuthContext context =
                new AuthContext(cmd.account(), cmd.amount(), cmd.mcc(), cmd.merchantId(), now, available, velocity);
        RuleResult ruleResult = ruleEngine.evaluate(context);
        if (ruleResult.decision() == Decision.DECLINE) {
            String reason = ruleResult.reasonCode();
            persistDeclined(authId, cmd, cardRef, reason, idempotencyKey, now);
            log.info("authorization {} DECLINED by rule {} reason {}", authId, ruleResult.ruleId(), reason);
            return authId;
        }

        AuthorizationEntity auth = new AuthorizationEntity(
                authId,
                cmd.account().value(),
                cmd.amount().amountMinor(),
                currency.getCurrencyCode(),
                cmd.mcc(),
                cmd.merchantId(),
                cmd.tokenId().map(TokenId::value).orElse(null),
                cardRef,
                AuthorizationStatus.AUTHORIZING,
                idempotencyKey,
                now);
        authorizations.save(auth);
        holds.place(authId, cmd.account(), cmd.amount(), now);
        log.info(
                "authorization {} AUTHORIZING hold placed amount={}",
                authId,
                cmd.amount().amountMinor());
        return authId;
    }

    private AuthorizationEntity persistDeclined(
            UUID authId, AuthorizeCommand cmd, String cardRef, String reason, String idempotencyKey, Instant now) {
        AuthorizationEntity declined = new AuthorizationEntity(
                authId,
                cmd.account().value(),
                cmd.amount().amountMinor(),
                cmd.amount().currency().getCurrencyCode(),
                cmd.mcc(),
                cmd.merchantId(),
                cmd.tokenId().map(TokenId::value).orElse(null),
                cardRef,
                AuthorizationStatus.DECLINED,
                idempotencyKey,
                now);
        declined.recordDeclineReason(reason);
        authorizations.save(declined);
        events.emit(declined, AuthorizationLifecycleEvent.DECLINED, reason, now);
        return declined;
    }

    /** Phase 2: the bounded partner authorize call and its finalize/compensate branch. */
    void runPartnerAuthorizeLeg(UUID authId) {
        AuthorizationEntity auth = authorizations.findById(authId).orElseThrow(() -> new AuthorizationNotFound(authId));
        if (auth.getStatus() != AuthorizationStatus.AUTHORIZING) {
            return; // declined in phase 1 (or already finalized): nothing to call the partner about
        }
        PartnerAuthOutcome outcome = partnerClient.authorize(
                authId.toString(),
                auth.getCardRef(),
                auth.getAmountMinor(),
                auth.getCurrency(),
                auth.getMcc(),
                auth.getMerchantId());
        switch (outcome.kind()) {
            case APPROVED -> finalizer.markAuthorized(
                    authId, outcome.partnerRef().orElseThrow());
            case DECLINED -> finalizer.declineByPartner(authId, outcome.reason().orElse(REASON_PARTNER_DECLINED));
            case NO_RESPONSE -> compensation.compensate(authId, REASON_PARTNER_TIMEOUT);
        }
    }

    // --- capture ---------------------------------------------------------------------------------

    /** Capture (settle) an authorized hold, optionally for a partial amount. */
    public AuthorizationView capture(UUID authId, Optional<Money> amountOverride, String idempotencyKey) {
        String key = CAPTURE_SCOPE + requireKey(idempotencyKey);
        String requestHash = hash("CAPTURE|%s|%s"
                .formatted(
                        authId,
                        amountOverride.map(m -> String.valueOf(m.amountMinor())).orElse("full")));
        guard.execute(key, requestHash, () -> stored(captureInTx(authId, amountOverride)));
        return view(authId);
    }

    /**
     * The capture body, executed inside the idempotency guard's transaction. The partner capture call
     * runs <em>before</em> the {@code SELECT ... FOR UPDATE} so no row lock is held across the network
     * (review W1); the partner is idempotent on the requestId and the ledger post is idempotent, so a
     * rollback (e.g. a partner failure) + retry re-drives the whole step safely.
     */
    UUID captureInTx(UUID authId, Optional<Money> amountOverride) {
        Instant now = clock.instant();
        AuthPreview preview = authorizations.findPreview(authId).orElseThrow(() -> new AuthorizationNotFound(authId));
        if (preview.status() != AuthorizationStatus.AUTHORIZED) {
            throw new IllegalAuthorizationState(authId, preview.status(), "capture");
        }
        Currency currency = Currency.getInstance(preview.currency());
        Money amount = amountOverride.orElseGet(() -> Money.of(preview.amountMinor(), currency));
        validateCaptureAmount(authId, amount, preview.amountMinor(), currency);

        // Idempotent partner capture, made before the row lock is acquired (no FOR UPDATE held across it).
        partnerClient.capture(authId + "|capture", preview.partnerRef(), amount.amountMinor());

        AuthorizationEntity auth =
                authorizations.findByIdForUpdate(authId).orElseThrow(() -> new AuthorizationNotFound(authId));
        if (auth.getStatus() != AuthorizationStatus.AUTHORIZED) {
            throw new IllegalAuthorizationState(authId, auth.getStatus(), "capture");
        }
        settlement.postCapture(authId, AccountId.of(auth.getAccountId()), amount, now);
        holds.releaseActive(authId, now);
        auth.recordCapturedAmount(amount.amountMinor());
        auth.transitionTo(AuthorizationStatus.CAPTURED, now);
        authorizations.save(auth);
        events.emit(auth, AuthorizationLifecycleEvent.CAPTURED, null, now);
        log.info("authorization {} CAPTURED amount={}", authId, amount.amountMinor());
        return authId;
    }

    private void validateCaptureAmount(UUID authId, Money amount, long authorizedMinor, Currency currency) {
        if (!amount.currency().equals(currency)) {
            throw new IllegalArgumentException("capture currency must match the authorization currency");
        }
        if (!amount.isPositive() || amount.amountMinor() > authorizedMinor) {
            throw new IllegalArgumentException("capture amount must be in (0, authorized] for authorization " + authId);
        }
    }

    // --- reverse ---------------------------------------------------------------------------------

    /** Reverse an authorization: release the hold (AUTHORIZED) or post the inverse settlement (CAPTURED). */
    public AuthorizationView reverse(UUID authId, String idempotencyKey) {
        String key = REVERSE_SCOPE + requireKey(idempotencyKey);
        String requestHash = hash("REVERSE|" + authId);
        guard.execute(key, requestHash, () -> stored(reverseInTx(authId)));
        return view(authId);
    }

    /**
     * The reverse body, executed inside the idempotency guard's transaction. The best-effort partner
     * reverse runs <em>before</em> the {@code SELECT ... FOR UPDATE} so no row lock is held across the
     * network (review W1). A reversal of a CAPTURED authorization posts the inverse of the amount
     * actually <em>captured</em> (review C1), not the authorized amount.
     */
    UUID reverseInTx(UUID authId) {
        Instant now = clock.instant();
        AuthPreview preview = authorizations.findPreview(authId).orElseThrow(() -> new AuthorizationNotFound(authId));
        Currency currency = Currency.getInstance(preview.currency());
        if (preview.status() != AuthorizationStatus.AUTHORIZED && preview.status() != AuthorizationStatus.CAPTURED) {
            throw new IllegalAuthorizationState(authId, preview.status(), "reverse");
        }
        bestEffortPartnerReverse(authId, preview.partnerRef());

        AuthorizationEntity auth =
                authorizations.findByIdForUpdate(authId).orElseThrow(() -> new AuthorizationNotFound(authId));
        AuthorizationStatus status = auth.getStatus();
        if (status != AuthorizationStatus.AUTHORIZED && status != AuthorizationStatus.CAPTURED) {
            throw new IllegalAuthorizationState(authId, status, "reverse");
        }
        if (status == AuthorizationStatus.CAPTURED) {
            long capturedMinor =
                    auth.getCapturedAmountMinor() != null ? auth.getCapturedAmountMinor() : auth.getAmountMinor();
            settlement.postCaptureReversal(
                    authId, AccountId.of(auth.getAccountId()), Money.of(capturedMinor, currency), now);
            log.info("authorization {} REVERSED (inverse of captured {} posted)", authId, capturedMinor);
        } else {
            log.info("authorization {} REVERSED (hold released)", authId);
        }
        holds.releaseActive(authId, now);
        auth.recordDeclineReason(REASON_REVERSED);
        auth.transitionTo(AuthorizationStatus.REVERSED, now);
        authorizations.save(auth);
        events.emit(auth, AuthorizationLifecycleEvent.REVERSED, REASON_REVERSED, now);
        return authId;
    }

    /** Best-effort idempotent partner reverse; a partner failure must not block releasing the hold. */
    private void bestEffortPartnerReverse(UUID authId, String partnerRef) {
        if (partnerRef == null || partnerRef.isBlank()) {
            return;
        }
        try {
            partnerClient.reverse(authId + "|reverse", partnerRef);
        } catch (PartnerUnavailableException e) {
            log.warn("best-effort partner reverse failed authorization={}; releasing hold regardless", authId);
        }
    }

    // --- reads -----------------------------------------------------------------------------------

    /** Full state of an authorization. */
    @Transactional(readOnly = true)
    public AuthorizationView view(UUID authId) {
        return authorizations
                .findById(authId)
                .map(AuthorizationSaga::toView)
                .orElseThrow(() -> new AuthorizationNotFound(authId));
    }

    /** Posted + available balance for an account (available reflects this module's active holds). */
    @Transactional(readOnly = true)
    public Balance balanceOf(AccountId account) {
        return ledger.balanceOf(account);
    }

    /**
     * Operator/observability read (review W3, QA #3): how many compensations reached terminal {@code
     * REVERSED} without a confirmed partner reverse. Must read zero — a non-zero value flags a possible
     * dangling partner-side authorization. Spec 08 can surface this as a Micrometer gauge.
     */
    @Override
    public long danglingPartnerReverseFinalizations() {
        return finalizer.danglingPartnerReverseFinalizations();
    }

    /** Operator/observability read: authorizations still {@code COMPENSATING} (outstanding partner reverses). */
    @Override
    public long outstandingPartnerObligations() {
        return finalizer.outstandingPartnerObligations();
    }

    /** Observability read (Spec 08): how many authorizations are currently in {@code status}. */
    @Override
    @Transactional(readOnly = true)
    public long authorizationsInStatus(AuthorizationStatus status) {
        return authorizations.countByStatus(status);
    }

    static AuthorizationView toView(AuthorizationEntity auth) {
        Currency currency = Currency.getInstance(auth.getCurrency());
        return new AuthorizationView(
                auth.getId(),
                AccountId.of(auth.getAccountId()),
                Money.of(auth.getAmountMinor(), currency),
                auth.getMcc(),
                auth.getMerchantId(),
                Optional.ofNullable(auth.getTokenId()),
                auth.getStatus(),
                Optional.ofNullable(auth.getPartnerRef()),
                Optional.ofNullable(auth.getDeclineReason()),
                auth.getCreatedAt(),
                auth.getUpdatedAt());
    }

    private static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        return idempotencyKey;
    }

    private String hash(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** Wrap a guarded result id with its canonical JSON so the idempotency record can store/replay it. */
    private StoredResult<UUID> stored(UUID id) {
        try {
            return StoredResult.of(id, objectMapper.writeValueAsString(id));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize authorization id", e);
        }
    }
}
