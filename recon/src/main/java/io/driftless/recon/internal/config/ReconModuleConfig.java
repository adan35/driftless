package io.driftless.recon.internal.config;

import io.driftless.recon.internal.persistence.ReconciliationResultEntity;
import io.driftless.recon.internal.persistence.ReconciliationResultRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the Spec 07 module so any Spring Boot application that component-scans {@code io.driftless}
 * (the {@code app} monolith and this module's own test application) picks up the {@code
 * reconciliation_result} entity/repository, binds {@link ReconProperties}, and enables the scheduled
 * balance-proof job — without naming internal packages.
 *
 * <p>The entity/repository scans are scoped to this module's own persistence package so they neither
 * pull in nor relocate any sibling module's entities (the ledger, idempotency/outbox and auth
 * entities are entity-scanned by their own modules' configuration already on the classpath). Recon
 * reads those committed rows through their published Spring Data repositories — it is, by design, a
 * consumer of the other modules' committed state.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReconProperties.class)
@EntityScan(basePackageClasses = ReconciliationResultEntity.class)
@EnableJpaRepositories(basePackageClasses = ReconciliationResultRepository.class)
public class ReconModuleConfig {}
