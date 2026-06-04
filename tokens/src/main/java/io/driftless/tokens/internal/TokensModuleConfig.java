package io.driftless.tokens.internal;

import io.driftless.tokens.internal.persistence.TokenEntity;
import io.driftless.tokens.internal.persistence.TokenRepository;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Wires the Spec 06 module's JPA persistence so any Spring Boot application that component-scans
 * {@code io.driftless} (the {@code app} monolith and this module's own test application) picks up the
 * {@code token} / {@code token_status_history} entities and repositories without naming internal
 * packages.
 *
 * <p>The entity/repository scans are scoped to this module's persistence package so they neither pull
 * in nor depend on any sibling module, preserving the strictly-inward dependency direction. The Spec
 * 02 guard, outbox writer and {@code ObjectMapper} this module reuses are contributed by the
 * idempotency module's own configuration/auto-configuration already on the classpath.
 */
@Configuration
@EntityScan(basePackageClasses = TokenEntity.class)
@EnableJpaRepositories(basePackageClasses = TokenRepository.class)
public class TokensModuleConfig {}
