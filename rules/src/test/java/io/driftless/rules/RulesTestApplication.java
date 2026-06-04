package io.driftless.rules;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring Boot configuration for the rule engine's slice tests.
 *
 * <p>Component-scans only {@code io.driftless.rules}, so the engine wires in isolation — no other
 * module, and (deliberately) no datasource auto-configuration is pulled in, which is itself part of
 * proving the hot path has no DB dependency.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "io.driftless.rules")
public class RulesTestApplication {}
