package io.driftless.idempotency;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application for the {@code idempotency} library's integration tests.
 *
 * <p>The production {@code idempotency} module is a library with no {@code main}; this test-only
 * application boots a real JPA + Flyway + scheduling context (scoped to {@code io.driftless} so this
 * module's {@code IdempotencyModuleConfig} wires its entities, repositories and the relay, and the
 * ledger's {@code LedgerPersistenceConfig} wires the ledger for the atomicity test) against a
 * Testcontainers Postgres instance. It is never packaged.
 */
@SpringBootApplication(scanBasePackages = "io.driftless")
public class IdempotencyTestApplication {}
