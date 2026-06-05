package io.driftless.auth.web;

import io.driftless.auth.api.UnknownAccountException;
import io.driftless.common.error.DomainException;
import io.driftless.common.money.CurrencyMismatchException;
import io.driftless.idempotency.api.IdempotencyConflict;
import io.driftless.ledger.api.BalanceInvariantViolation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized error mapping for the account-lifecycle + funding + statement endpoints. Mirrors {@link
 * AuthExceptionHandler}'s code-carrying RFC-7807 bodies (see {@link ProblemSupport}) so the whole
 * public surface speaks the same stable {@code code} vocabulary.
 *
 * <ul>
 *   <li>{@link MissingIdempotencyKeyException} / validation errors / {@link IllegalArgumentException}
 *       → {@code 400 Bad Request}
 *   <li>{@link UnknownAccountException} → {@code 404 Not Found} ({@code ACCOUNT_NOT_FOUND})
 *   <li>{@link IdempotencyConflict} → {@code 409 Conflict}
 *   <li>{@link CurrencyMismatchException} / {@link BalanceInvariantViolation} and any other {@link
 *       DomainException} → {@code 422 Unprocessable Entity}
 * </ul>
 */
@RestControllerAdvice(assignableTypes = AccountsController.class)
class AccountsExceptionHandler {

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    ProblemDetail handleMissingKey(MissingIdempotencyKeyException ex) {
        return ProblemSupport.of(HttpStatus.BAD_REQUEST, ProblemSupport.MISSING_IDEMPOTENCY_KEY, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemSupport.of(HttpStatus.BAD_REQUEST, ProblemSupport.VALIDATION_FAILED, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidBody(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("invalid request body");
        return ProblemSupport.of(HttpStatus.BAD_REQUEST, ProblemSupport.VALIDATION_FAILED, detail);
    }

    @ExceptionHandler(UnknownAccountException.class)
    ProblemDetail handleUnknownAccount(UnknownAccountException ex) {
        return ProblemSupport.of(HttpStatus.NOT_FOUND, ProblemSupport.ACCOUNT_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflict.class)
    ProblemDetail handleConflict(IdempotencyConflict ex) {
        return ProblemSupport.of(HttpStatus.CONFLICT, ProblemSupport.IDEMPOTENCY_CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        return ProblemSupport.of(HttpStatus.UNPROCESSABLE_ENTITY, ProblemSupport.CURRENCY_MISMATCH, ex.getMessage());
    }

    @ExceptionHandler(BalanceInvariantViolation.class)
    ProblemDetail handleUnbalanced(BalanceInvariantViolation ex) {
        return ProblemSupport.of(
                HttpStatus.UNPROCESSABLE_ENTITY, ProblemSupport.BALANCE_INVARIANT_VIOLATION, ex.getMessage());
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException ex) {
        return ProblemSupport.of(HttpStatus.UNPROCESSABLE_ENTITY, ProblemSupport.DOMAIN_ERROR, ex.getMessage());
    }
}
