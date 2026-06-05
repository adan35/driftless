package io.driftless.auth.internal.config;

import io.driftless.auth.internal.persistence.AuthorizationEntity;
import io.driftless.auth.internal.persistence.AuthorizationRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the Spec 03 module so any Spring Boot application that component-scans {@code io.driftless}
 * (the {@code app} monolith and this module's own test application) picks up the {@code authorization}
 * / {@code hold} entities and repositories, binds {@link AuthProperties}, and enables the scheduled
 * crash-recovery sweep — without naming internal packages.
 *
 * <p>The entity/repository scans are scoped to this module's persistence package so they neither pull
 * in nor depend on any sibling module, preserving the strictly-inward dependency direction. The Spec
 * 02 guard/outbox/{@code ObjectMapper}, the Spec 05 {@code RuleEngine}/{@code VelocityStore}, the Spec
 * 06 {@code TokenService} and the ledger this module reuses are all contributed by their own modules'
 * configuration already on the classpath. The real {@code HoldView} bean this module publishes
 * cleanly replaces the ledger's {@code @ConditionalOnMissingBean} no-op.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuthProperties.class)
@EntityScan(basePackageClasses = AuthorizationEntity.class)
@EnableJpaRepositories(basePackageClasses = AuthorizationRepository.class)
public class AuthModuleConfig {}
