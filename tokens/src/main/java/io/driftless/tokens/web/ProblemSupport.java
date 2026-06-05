package io.driftless.tokens.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC-7807 {@link ProblemDetail} bodies that carry a stable, machine-readable {@code code} (and
 * matching {@code type} URI) alongside the human-readable {@code detail}. Mirrors the auth/accounts
 * surface so clients branch on the code, not the prose, across the whole API.
 */
final class ProblemSupport {

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String MISSING_IDEMPOTENCY_KEY = "MISSING_IDEMPOTENCY_KEY";
    static final String TOKEN_NOT_FOUND = "TOKEN_NOT_FOUND";
    static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    static final String ILLEGAL_TOKEN_TRANSITION = "ILLEGAL_TOKEN_TRANSITION";

    private static final String TYPE_PREFIX = "https://driftless.io/errors/";

    private ProblemSupport() {}

    static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setProperty("code", code);
        return problem;
    }
}
