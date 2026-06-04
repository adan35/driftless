package io.driftless.tokens;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application for the {@code tokens} library's integration tests.
 *
 * <p>The production {@code tokens} module is a library with no {@code main}; this test-only
 * application boots a real JPA + Flyway + web context (scoped to {@code io.driftless} so this
 * module's {@code TokensModuleConfig} wires its entities/repositories, and the idempotency module's
 * config/auto-configuration contributes the guard, outbox writer and {@code ObjectMapper}) against a
 * Testcontainers Postgres instance. It is never packaged.
 */
@SpringBootApplication(scanBasePackages = "io.driftless")
public class TokensTestApplication {}
