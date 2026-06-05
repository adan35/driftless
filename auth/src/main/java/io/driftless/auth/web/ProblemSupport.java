package io.driftless.auth.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC-7807 {@link ProblemDetail} bodies that carry a <strong>stable, machine-readable error
 * code</strong> in addition to the human-readable {@code detail} prose.
 *
 * <p>Clients branch on {@code code} (and the matching {@code type} URI) — e.g. {@code
 * IDEMPOTENCY_CONFLICT}, {@code ACCOUNT_NOT_FOUND}, {@code BALANCE_INVARIANT_VIOLATION} — rather than
 * parsing English. The same codes are used across the auth, accounts and tokens surfaces.
 */
final class ProblemSupport {

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String MISSING_IDEMPOTENCY_KEY = "MISSING_IDEMPOTENCY_KEY";
    static final String AUTHORIZATION_NOT_FOUND = "AUTHORIZATION_NOT_FOUND";
    static final String TOKEN_NOT_FOUND = "TOKEN_NOT_FOUND";
    static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    static final String ILLEGAL_AUTHORIZATION_STATE = "ILLEGAL_AUTHORIZATION_STATE";
    static final String BALANCE_INVARIANT_VIOLATION = "BALANCE_INVARIANT_VIOLATION";
    static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    static final String DOMAIN_ERROR = "DOMAIN_ERROR";

    private static final String TYPE_PREFIX = "https://driftless.io/errors/";

    private ProblemSupport() {}

    /** A {@link ProblemDetail} for {@code status} with the stable {@code code} (and matching type URI). */
    static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setProperty("code", code);
        return problem;
    }
}
