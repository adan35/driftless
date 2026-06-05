package io.driftless.auth.web;

import io.driftless.auth.api.AuthorizationNotFound;
import io.driftless.auth.api.IllegalAuthorizationState;
import io.driftless.common.error.DomainException;
import io.driftless.idempotency.api.IdempotencyConflict;
import io.driftless.tokens.api.TokenNotFound;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized error mapping for the authorization endpoints, translating typed domain failures to HTTP
 * status codes (RFC 7807 {@link ProblemDetail} bodies).
 *
 * <ul>
 *   <li>{@link MissingIdempotencyKeyException} / validation errors / {@link IllegalArgumentException}
 *       → {@code 400 Bad Request}
 *   <li>{@link AuthorizationNotFound} / {@link TokenNotFound} → {@code 404 Not Found}
 *   <li>{@link IdempotencyConflict} (same key, different body) / {@link IllegalAuthorizationState}
 *       (state-machine rejection) → {@code 409 Conflict}
 *   <li>any other {@link DomainException} (e.g. a ledger balance/currency violation) → {@code 422
 *       Unprocessable Entity}
 * </ul>
 *
 * <p>Spring selects the most specific handler, so the {@link DomainException} fallback only catches
 * domain errors not named above.
 */
@RestControllerAdvice(assignableTypes = AuthorizationController.class)
class AuthExceptionHandler {

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    ProblemDetail handleMissingKey(MissingIdempotencyKeyException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidBody(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("invalid request body");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(AuthorizationNotFound.class)
    ProblemDetail handleAuthNotFound(AuthorizationNotFound ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(TokenNotFound.class)
    ProblemDetail handleTokenNotFound(TokenNotFound ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflict.class)
    ProblemDetail handleConflict(IdempotencyConflict ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalAuthorizationState.class)
    ProblemDetail handleIllegalState(IllegalAuthorizationState ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }
}
