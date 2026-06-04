package io.driftless.tokens.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.common.id.TokenId;
import io.driftless.idempotency.api.IdempotencyGuard;
import io.driftless.idempotency.api.IdempotentResult;
import io.driftless.idempotency.api.StoredResult;
import io.driftless.outbox.api.OutboxEvent;
import io.driftless.outbox.api.OutboxWriter;
import io.driftless.tokens.api.CreateTokenCommand;
import io.driftless.tokens.api.IllegalTokenTransition;
import io.driftless.tokens.api.Token;
import io.driftless.tokens.api.TokenNotFound;
import io.driftless.tokens.api.TokenService;
import io.driftless.tokens.api.TokenStatus;
import io.driftless.tokens.api.TokenTransition;
import io.driftless.tokens.internal.event.TokenStatusChanged;
import io.driftless.tokens.internal.persistence.TokenEntity;
import io.driftless.tokens.internal.persistence.TokenRepository;
import io.driftless.tokens.internal.persistence.TokenStatusHistoryEntity;
import io.driftless.tokens.internal.persistence.TokenStatusHistoryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The Spec 06 tokenization engine: enforces the {@link TokenTransition} state machine and propagates
 * every status change through the Spec 02 outbox, all under the Spec 02 idempotency guard.
 *
 * <p>It exposes two surfaces over one engine: the keyless public {@link TokenService} (idempotency
 * anchored on the token's resulting state — a transition whose token is already in the target state
 * is a safe no-op replay) and the {@link KeyedTokenService} the web layer uses (idempotency anchored
 * on the caller's {@code Idempotency-Key}, under which the strict state machine applies).
 *
 * <p><strong>Atomicity.</strong> The actual mutation — the {@code token} status update, the one
 * appended {@code token_status_history} row, and the one {@code TokenStatusChanged} outbox event —
 * runs inside the supplier passed to {@link IdempotencyGuard#execute}, which the guard runs in a
 * single transaction together with recording the idempotency key. The {@code OutboxWriter} appends
 * with {@code Propagation.MANDATORY}, so the event is guaranteed to share that transaction: status
 * write, history row and event commit or roll back as one. A retry replays the stored result and
 * runs the supplier zero times, so no duplicate history row or event is ever produced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class TokenLifecycleService implements TokenService, KeyedTokenService {

    /**
     * Reserved prefix for guard keys derived from a web caller's {@code Idempotency-Key} (Spec 06
     * review fix W2). Derived internal keys start with {@code "tokens:"}; web keys are stored under
     * this non-overlapping prefix so a caller-supplied key can never alias an internal operation's key
     * and false-replay it.
     */
    private static final String WEB_KEY_PREFIX = "tokens.web:";

    private final TokenRepository tokens;
    private final TokenStatusHistoryRepository history;
    private final IdempotencyGuard guard;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // --- public keyless TokenService (state-anchored idempotency) --------------------------------

    @Override
    public Token create(CreateTokenCommand cmd) {
        return doCreate(cmd, Optional.empty());
    }

    @Override
    public Token activate(TokenId id) {
        return apply(id, TokenTransition.ACTIVATE, Optional.empty());
    }

    @Override
    public Token suspend(TokenId id) {
        return apply(id, TokenTransition.SUSPEND, Optional.empty());
    }

    @Override
    public Token resume(TokenId id) {
        return apply(id, TokenTransition.RESUME, Optional.empty());
    }

    @Override
    public Token deactivate(TokenId id) {
        return apply(id, TokenTransition.DEACTIVATE, Optional.empty());
    }

    // --- internal keyed KeyedTokenService (caller-key idempotency) -------------------------------

    @Override
    public Token create(CreateTokenCommand cmd, String idempotencyKey) {
        return doCreate(cmd, Optional.of(requireKey(idempotencyKey)));
    }

    @Override
    public Token activate(TokenId id, String idempotencyKey) {
        return apply(id, TokenTransition.ACTIVATE, Optional.of(requireKey(idempotencyKey)));
    }

    @Override
    public Token suspend(TokenId id, String idempotencyKey) {
        return apply(id, TokenTransition.SUSPEND, Optional.of(requireKey(idempotencyKey)));
    }

    @Override
    public Token resume(TokenId id, String idempotencyKey) {
        return apply(id, TokenTransition.RESUME, Optional.of(requireKey(idempotencyKey)));
    }

    @Override
    public Token deactivate(TokenId id, String idempotencyKey) {
        return apply(id, TokenTransition.DEACTIVATE, Optional.of(requireKey(idempotencyKey)));
    }

    @Override
    public Token find(TokenId id) {
        return tokens.findById(id.value()).map(TokenLifecycleService::toToken).orElseThrow(() -> new TokenNotFound(id));
    }

    // --- engine ----------------------------------------------------------------------------------

    private Token doCreate(CreateTokenCommand cmd, Optional<String> callerKey) {
        Optional<TokenId> explicit = cmd.tokenId();
        // Keyless create with an explicit id is idempotent on that id: a token that already exists is
        // returned unchanged (no new row, no event). Without an explicit id there is no anchor, so a
        // keyless create always mints a new token.
        if (callerKey.isEmpty() && explicit.isPresent()) {
            Optional<TokenEntity> existing = tokens.findById(explicit.get().value());
            if (existing.isPresent()) {
                log.info(
                        "token {} create replay; already {}",
                        explicit.get(),
                        existing.get().getStatus());
                return toToken(existing.get());
            }
        }
        String key = callerKey
                .map(TokenLifecycleService::webKey)
                .orElseGet(() -> "tokens:create:"
                        + explicit.map(TokenId::toString)
                                .orElseGet(() -> UUID.randomUUID().toString()));
        String requestHash = hash("CREATE|" + cmd.cardRef() + "|"
                + explicit.map(TokenId::toString).orElse(""));
        IdempotentResult<Token> result = guard.execute(key, requestHash, () -> {
            TokenId id = explicit.orElseGet(TokenId::newId);
            Optional<TokenEntity> inTx = tokens.findById(id.value());
            if (inTx.isPresent()) {
                Token existing = toToken(inTx.get());
                return StoredResult.of(existing, json(existing));
            }
            Instant now = clock.instant();
            TokenEntity entity =
                    new TokenEntity(id.value(), cmd.cardRef(), TokenStatus.INACTIVE, TokenTransition.CREATE, now, now);
            tokens.save(entity);
            recordTransition(id, null, TokenStatus.INACTIVE, TokenTransition.CREATE, now);
            Token token = toToken(entity);
            log.info("token {} created -> {}", id, TokenStatus.INACTIVE);
            return StoredResult.of(token, json(token));
        });
        return result.value();
    }

    private Token apply(TokenId id, TokenTransition transition, Optional<String> callerKey) {
        // Keyless replay rule (Spec 06 review fix W1): a transition is an idempotent replay ONLY when
        // the SAME transition was the one that last advanced the token (and the token is already in
        // that transition's target state). Any other transition that merely happens to point at the
        // current state — e.g. resume of an ACTIVE token last advanced by activate — is NOT a replay
        // and falls through to the strict edge check, which rejects it with IllegalTokenTransition.
        // Under a caller key the guard handles replay, so the strict state machine always applies.
        if (callerKey.isEmpty()) {
            TokenEntity current = tokens.findById(id.value()).orElseThrow(() -> new TokenNotFound(id));
            if (current.getStatus() == transition.target() && current.getLastTransition() == transition) {
                log.info("token {} {} replay; already {} via the same transition", id, transition, current.getStatus());
                return toToken(current);
            }
        }
        // I1: the per-occurrence sequence number is only needed to derive the keyless key, so the
        // COUNT(*) runs only on the keyless path — the keyed path never pays for it.
        String key = callerKey
                .map(TokenLifecycleService::webKey)
                .orElseGet(() -> "tokens:" + id + ":" + transition + ":" + history.countByTokenId(id.value()));
        String requestHash = hash(transition.name() + "|" + id);
        IdempotentResult<Token> result = guard.execute(key, requestHash, () -> {
            // C1: load FOR UPDATE so concurrent transitions on this token serialize. The second
            // transaction blocks here, then re-reads the committed status and re-checks the edge below.
            TokenEntity entity = tokens.findByIdForUpdate(id.value()).orElseThrow(() -> new TokenNotFound(id));
            TokenStatus liveFrom = entity.getStatus();
            if (!transition.isLegalFrom(liveFrom)) {
                // Strict edge enforcement; rolls back the guard's claim so nothing is written.
                throw new IllegalTokenTransition(id, liveFrom, transition);
            }
            TokenStatus target = transition.target();
            Instant now = clock.instant();
            entity.transitionTo(target, transition, now);
            tokens.save(entity);
            recordTransition(id, liveFrom, target, transition, now);
            Token token = toToken(entity);
            log.info("token {} {} {} -> {}", id, transition, liveFrom, target);
            return StoredResult.of(token, json(token));
        });
        return result.value();
    }

    /** Append the audit row and the propagation event together, inside the guard's transaction. */
    private void recordTransition(
            TokenId id, TokenStatus from, TokenStatus to, TokenTransition transition, Instant occurredAt) {
        history.save(new TokenStatusHistoryEntity(UUID.randomUUID(), id.value(), from, to, transition, occurredAt));
        TokenStatusChanged payload = new TokenStatusChanged(id, from, to, occurredAt);
        outbox.append(new OutboxEvent(
                TokenStatusChanged.AGGREGATE_TYPE,
                id.toString(),
                TokenStatusChanged.EVENT_TYPE,
                json(payload),
                occurredAt));
    }

    private static Token toToken(TokenEntity entity) {
        return new Token(TokenId.of(entity.getId()), entity.getCardRef(), entity.getStatus(), entity.getUpdatedAt());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize token payload", e);
        }
    }

    /** SHA-256 fingerprint of the canonical request, kept within the guard's request-hash column. */
    private static String hash(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        return idempotencyKey;
    }

    /**
     * Namespace a web caller's {@code Idempotency-Key} under the reserved {@link #WEB_KEY_PREFIX} so it
     * can never alias a derived internal key (Spec 06 review fix W2).
     */
    private static String webKey(String callerKey) {
        return WEB_KEY_PREFIX + callerKey;
    }
}
