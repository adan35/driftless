package io.driftless.tokens.web;

import io.driftless.common.id.TokenId;
import io.driftless.tokens.api.Token;
import io.driftless.tokens.internal.KeyedTokenService;
import io.driftless.tokens.web.dto.CreateTokenRequest;
import io.driftless.tokens.web.dto.TokenResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST surface for the tokenization lifecycle (Spec 06): routing and (de)serialization only —
 * all business logic lives in the service.
 *
 * <p>Every mutating endpoint reads the {@code Idempotency-Key} header (missing/blank ⇒ {@code 400};
 * the same key with a different body ⇒ {@code 409} via {@code IdempotencyConflict}; a replay ⇒ the
 * original {@code 2xx} result) and threads it into the {@link KeyedTokenService} so the Spec 02 guard
 * keys replay on the caller's key. The read endpoint exposes the current status so the auth saga can
 * gate authorizations on {@code status == ACTIVE}.
 */
@Slf4j
@RestController
@RequestMapping("/tokens")
@RequiredArgsConstructor
public class TokenController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final KeyedTokenService tokens;

    @PostMapping
    public ResponseEntity<TokenResponse> create(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateTokenRequest request) {
        Token token = tokens.create(request.toCommand(), requireKey(idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(TokenResponse.from(token));
    }

    @PostMapping("/{id}/activate")
    public TokenResponse activate(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return TokenResponse.from(tokens.activate(TokenId.of(id), requireKey(idempotencyKey)));
    }

    @PostMapping("/{id}/suspend")
    public TokenResponse suspend(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return TokenResponse.from(tokens.suspend(TokenId.of(id), requireKey(idempotencyKey)));
    }

    @PostMapping("/{id}/resume")
    public TokenResponse resume(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return TokenResponse.from(tokens.resume(TokenId.of(id), requireKey(idempotencyKey)));
    }

    @PostMapping("/{id}/deactivate")
    public TokenResponse deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return TokenResponse.from(tokens.deactivate(TokenId.of(id), requireKey(idempotencyKey)));
    }

    @GetMapping("/{id}")
    public TokenResponse find(@PathVariable UUID id) {
        return TokenResponse.from(tokens.find(TokenId.of(id)));
    }

    /** Enforce the convention: a mutating request without an {@code Idempotency-Key} is a 400. */
    private static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException(IDEMPOTENCY_KEY_HEADER);
        }
        return idempotencyKey;
    }
}
