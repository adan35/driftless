package io.driftless.rules.internal;

import io.driftless.rules.config.RuleProperties;

/**
 * Plain-Java wiring of the engine for unit tests — no Spring context, which is itself evidence that
 * {@code evaluate} needs no container and no datasource. Construct over an authored {@link
 * RuleProperties}, mutate the properties and {@link #refresh()} to exercise live rule changes.
 */
final class EngineHarness {

    private final RuleProperties properties;
    private final RuleCache cache;
    final RuleEvaluator evaluator;

    EngineHarness(RuleProperties properties) {
        this.properties = properties;
        this.cache = new RuleCache(properties, new RuleCompiler());
        this.cache.refresh();
        this.evaluator = new RuleEvaluator(cache);
    }

    RuleProperties properties() {
        return properties;
    }

    boolean refresh() {
        return cache.refresh();
    }

    int activeRuleCount() {
        return cache.current().size();
    }
}
