package io.driftless.auth.web;

import io.driftless.auth.api.AuthorizationView;
import io.driftless.auth.internal.AuthorizationSaga;
import io.driftless.auth.web.dto.AuthorizationResponse;
import io.driftless.auth.web.dto.AuthorizeRequest;
import io.driftless.auth.web.dto.AuthorizeResponse;
import io.driftless.auth.web.dto.BalanceResponse;
import io.driftless.auth.web.dto.CaptureRequest;
import io.driftless.auth.web.dto.StatusResponse;
import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import jakarta.validation.Valid;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST surface for the Spec 03 saga: routing and (de)serialization only — all orchestration lives
 * in {@link AuthorizationSaga}.
 *
 * <p>Every mutating endpoint reads the {@code Idempotency-Key} header (missing/blank ⇒ {@code 400};
 * the same key with a different body ⇒ {@code 409} via {@code IdempotencyConflict}; a replay ⇒ the
 * original {@code 2xx} result) and threads it into the saga so the Spec 02 guard keys replay on the
 * caller's key. JPA entities are never returned — every response is a DTO mapped from the {@link
 * AuthorizationView} read model.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthorizationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final AuthorizationSaga saga;

    @PostMapping("/authorizations")
    public ResponseEntity<AuthorizeResponse> authorize(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AuthorizeRequest request) {
        AuthorizationView view = saga.authorize(request.toCommand(), requireKey(idempotencyKey));
        log.info("authorize id={} status={}", view.id(), view.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthorizeResponse.from(view));
    }

    @PostMapping("/authorizations/{id}/capture")
    public StatusResponse capture(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) CaptureRequest request) {
        AuthorizationView authorized = saga.view(id);
        Optional<Money> amount = Optional.ofNullable(request)
                .map(CaptureRequest::amountMinor)
                .map(minor -> Money.of(minor, authorized.amount().currency()));
        AuthorizationView view = saga.capture(id, amount, requireKey(idempotencyKey));
        return StatusResponse.from(view);
    }

    @PostMapping("/authorizations/{id}/reverse")
    public StatusResponse reverse(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return StatusResponse.from(saga.reverse(id, requireKey(idempotencyKey)));
    }

    @GetMapping("/authorizations/{id}")
    public AuthorizationResponse find(@PathVariable UUID id) {
        return AuthorizationResponse.from(saga.view(id));
    }

    @GetMapping("/accounts/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        return BalanceResponse.from(saga.balanceOf(AccountId.of(id)));
    }

    /** Enforce the convention: a mutating request without an {@code Idempotency-Key} is a 400. */
    private static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException(IDEMPOTENCY_KEY_HEADER);
        }
        return idempotencyKey;
    }
}
