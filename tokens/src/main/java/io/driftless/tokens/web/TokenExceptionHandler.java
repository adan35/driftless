package io.driftless.tokens.web;

import io.driftless.idempotency.api.IdempotencyConflict;
import io.driftless.tokens.api.IllegalTokenTransition;
import io.driftless.tokens.api.TokenNotFound;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized error mapping for the token endpoints, translating typed domain failures to HTTP
 * status codes (RFC 7807 {@link ProblemDetail} bodies).
 *
 * <ul>
 *   <li>{@link MissingIdempotencyKeyException} / validation errors / {@link IllegalArgumentException}
 *       → {@code 400 Bad Request}
 *   <li>{@link TokenNotFound} → {@code 404 Not Found}
 *   <li>{@link IdempotencyConflict} (same key, different body) → {@code 409 Conflict}
 *   <li>{@link IllegalTokenTransition} (state machine rejection) → {@code 422 Unprocessable Entity}
 * </ul>
 */
@RestControllerAdvice(assignableTypes = TokenController.class)
class TokenExceptionHandler {

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

    @ExceptionHandler(TokenNotFound.class)
    ProblemDetail handleNotFound(TokenNotFound ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflict.class)
    ProblemDetail handleConflict(IdempotencyConflict ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalTokenTransition.class)
    ProblemDetail handleIllegalTransition(IllegalTokenTransition ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }
}
