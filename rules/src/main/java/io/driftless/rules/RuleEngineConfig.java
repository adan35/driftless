package io.driftless.rules;

import io.driftless.rules.config.RuleProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Module-level wiring for the rule engine, picked up by any Spring Boot application that
 * component-scans {@code io.driftless} (the {@code app} monolith, and the module's own test
 * application).
 *
 * <ul>
 *   <li>{@link EnableConfigurationProperties} binds {@link RuleProperties} — the rules authored as
 *       data that the engine compiles and reloads off the hot path.
 *   <li>{@link EnableScheduling} drives {@link io.driftless.rules.internal.RuleCache}'s off-hot-path
 *       refresh on the configured interval.
 * </ul>
 *
 * <p>The compiler, cache, evaluator, and velocity store are {@code @Component}/{@code @Service} beans
 * discovered by the same scan. No datasource, web, or JPA is wired — the hot path is in-memory by
 * construction, so the engine adds no DB dependency the fault harness (Spec 07) could stall.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RuleProperties.class)
public class RuleEngineConfig {}
