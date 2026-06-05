package io.driftless.recon;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application for the {@code recon} library's integration tests and the
 * property-based gate.
 *
 * <p>The production {@code recon} module is a library with no {@code main}; this test-only application
 * boots a real JPA + Flyway + web context (scoped to {@code io.driftless} so this module's {@code
 * ReconModuleConfig} wires its entity/repository, and the ledger, idempotency, auth, rules and tokens
 * modules contribute their beans) against a Testcontainers Postgres. It is never packaged.
 */
@SpringBootApplication(scanBasePackages = "io.driftless")
public class ReconTestApplication {}
