package io.driftless.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.auth.api.AuthorizationNotFound;
import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.IllegalAuthorizationState;
import io.driftless.auth.api.UnknownAccountException;
import io.driftless.common.id.AccountId;
import io.driftless.common.money.CurrencyMismatchException;
import io.driftless.idempotency.api.IdempotencyConflict;
import io.driftless.ledger.api.BalanceInvariantViolation;
import io.driftless.tokens.api.TokenNotFound;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Unit coverage for the code-carrying RFC-7807 mapping in the auth + accounts {@code
 * @RestControllerAdvice}s. Asserts each typed domain failure maps to the right status AND carries the
 * stable, machine-readable {@code code} property clients branch on — including the defensive branches
 * (unbalanced / currency-mismatch / domain fallback) that the happy-path saga never reaches.
 */
class WebExceptionHandlerTest {

    private final AuthExceptionHandler auth = new AuthExceptionHandler();
    private final AccountsExceptionHandler accounts = new AccountsExceptionHandler();

    private static String code(ProblemDetail problem) {
        return (String) problem.getProperties().get("code");
    }

    @Test
    void authMappingsCarryStableCodes() {
        assertProblem(
                auth.handleMissingKey(new MissingIdempotencyKeyException("Idempotency-Key")),
                HttpStatus.BAD_REQUEST,
                "MISSING_IDEMPOTENCY_KEY");
        assertProblem(
                auth.handleIllegalArgument(new IllegalArgumentException("bad")),
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertProblem(
                auth.handleAuthNotFound(new AuthorizationNotFound(UUID.randomUUID())),
                HttpStatus.NOT_FOUND,
                "AUTHORIZATION_NOT_FOUND");
        assertProblem(
                auth.handleTokenNotFound(new TokenNotFound(io.driftless.common.id.TokenId.newId())),
                HttpStatus.NOT_FOUND,
                "TOKEN_NOT_FOUND");
        assertProblem(
                auth.handleConflict(new IdempotencyConflict("k", "a", "b")),
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT");
        assertProblem(
                auth.handleIllegalState(
                        new IllegalAuthorizationState(UUID.randomUUID(), AuthorizationStatus.REVERSED, "capture")),
                HttpStatus.CONFLICT,
                "ILLEGAL_AUTHORIZATION_STATE");
        assertProblem(
                auth.handleUnbalanced(new BalanceInvariantViolation("unbalanced")),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "BALANCE_INVARIANT_VIOLATION");
        assertProblem(
                auth.handleCurrencyMismatch(
                        new CurrencyMismatchException(Currency.getInstance("USD"), Currency.getInstance("EUR"))),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "CURRENCY_MISMATCH");
        assertProblem(
                auth.handleDomain(new UnknownAccountException(AccountId.newId())),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "DOMAIN_ERROR");
    }

    @Test
    void accountsMappingsCarryStableCodes() {
        assertProblem(
                accounts.handleMissingKey(new MissingIdempotencyKeyException("Idempotency-Key")),
                HttpStatus.BAD_REQUEST,
                "MISSING_IDEMPOTENCY_KEY");
        assertProblem(
                accounts.handleIllegalArgument(new IllegalArgumentException("bad")),
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertProblem(
                accounts.handleUnknownAccount(new UnknownAccountException(AccountId.newId())),
                HttpStatus.NOT_FOUND,
                "ACCOUNT_NOT_FOUND");
        assertProblem(
                accounts.handleConflict(new IdempotencyConflict("k", "a", "b")),
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT");
        assertProblem(
                accounts.handleCurrencyMismatch(
                        new CurrencyMismatchException(Currency.getInstance("USD"), Currency.getInstance("EUR"))),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "CURRENCY_MISMATCH");
        assertProblem(
                accounts.handleUnbalanced(new BalanceInvariantViolation("unbalanced")),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "BALANCE_INVARIANT_VIOLATION");
        assertProblem(
                accounts.handleDomain(new UnknownAccountException(AccountId.newId())),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "DOMAIN_ERROR");
    }

    private static void assertProblem(ProblemDetail problem, HttpStatus status, String code) {
        assertThat(problem.getStatus()).isEqualTo(status.value());
        assertThat(code(problem)).isEqualTo(code);
        assertThat(problem.getType().toString()).endsWith("/" + code);
    }
}
