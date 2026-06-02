package io.driftless.ledger;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application for the {@code ledger} library's integration tests.
 *
 * <p>The production {@code ledger} module is a library with no {@code main}; this test-only
 * application boots a real JPA + Flyway context (scoped to {@code io.driftless} so the ledger's
 * {@code LedgerPersistenceConfig} wires its entities and repositories) against a Testcontainers
 * Postgres instance. It is never packaged.
 */
@SpringBootApplication(scanBasePackages = "io.driftless")
public class LedgerTestApplication {}
