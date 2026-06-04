package io.driftless.idempotency.internal;

import io.driftless.idempotency.internal.persistence.IdempotencyRecordEntity;
import io.driftless.idempotency.internal.persistence.IdempotencyRecordRepository;
import io.driftless.outbox.internal.OutboxRelayProperties;
import io.driftless.outbox.internal.persistence.OutboxEventEntity;
import io.driftless.outbox.internal.persistence.OutboxEventRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the Spec 02 module's JPA persistence, the relay's scheduling, and its configuration
 * properties, so any Spring Boot application that component-scans {@code io.driftless} (the {@code
 * app} monolith, and this module's own test application) picks up the idempotency record and outbox
 * entities/repositories — and the scheduled relay — without naming internal packages.
 *
 * <p>The entity/repository scans are scoped to this module's two persistence packages so they neither
 * pull in nor depend on any other module, preserving the strictly-inward dependency direction. The
 * module's services and the relay are ordinary component-scanned stereotypes; only the default {@code
 * EventPublisher} is auto-configured (see {@link IdempotencyAutoConfiguration}) so it can be replaced.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties.class)
@EntityScan(basePackageClasses = {IdempotencyRecordEntity.class, OutboxEventEntity.class})
@EnableJpaRepositories(basePackageClasses = {IdempotencyRecordRepository.class, OutboxEventRepository.class})
public class IdempotencyModuleConfig {}
