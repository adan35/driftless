package io.driftless.ledger.internal;

import io.driftless.ledger.internal.persistence.AccountEntity;
import io.driftless.ledger.internal.persistence.AccountRepository;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Wires the ledger's JPA persistence so any Spring Boot application that component-scans {@code
 * io.driftless} (the {@code app} monolith, and the module's own test application) picks up the
 * ledger's entities and repositories without naming internal packages.
 *
 * <p>The scan is scoped to the ledger's persistence package so it neither pulls in nor depends on
 * any other module — preserving the strictly-inward dependency direction.
 */
@Configuration
@EntityScan(basePackageClasses = AccountEntity.class)
@EnableJpaRepositories(basePackageClasses = AccountRepository.class)
public class LedgerPersistenceConfig {}
