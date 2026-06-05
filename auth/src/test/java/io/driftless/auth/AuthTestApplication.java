package io.driftless.auth;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application for the {@code auth} library's integration tests.
 *
 * <p>The production {@code auth} module is a library with no {@code main}; this test-only application
 * boots a real JPA + Flyway + web context (scoped to {@code io.driftless} so this module's {@code
 * AuthModuleConfig} wires its entities/repositories and {@code HoldViewImpl}, and the ledger,
 * idempotency, rules and tokens modules contribute their beans) against a Testcontainers Postgres. It
 * is never packaged.
 */
@SpringBootApplication(scanBasePackages = "io.driftless")
public class AuthTestApplication {}
