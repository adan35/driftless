package io.driftless.observability.metrics;

import io.driftless.ledger.api.Ledger;
import io.driftless.rules.api.RuleEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the {@code @Primary} metrics decorators over the frozen cross-module contracts.
 *
 * <p>Each decorator is made primary so the whole monolith resolves the instrumented bean while the
 * feature modules keep providing the plain implementation. The real implementations are injected by
 * their stable bean names ({@code ledgerService}, {@code ruleEvaluator}) so there is no self-injection
 * cycle: the decorator is primary, the delegate is selected explicitly by qualifier. Neither the
 * ledger nor the rules module is modified — they never learn a meter exists.
 */
@Configuration
public class ObservabilityMetricsConfig {

    /** Bean name of the Spec 01 {@code LedgerService} (its {@code @Service} default name). */
    private static final String LEDGER_SERVICE_BEAN = "ledgerService";

    /** Bean name of the Spec 05 {@code RuleEvaluator} (its {@code @Service} default name). */
    private static final String RULE_EVALUATOR_BEAN = "ruleEvaluator";

    @Bean
    @Primary
    Ledger meteredLedger(@Qualifier(LEDGER_SERVICE_BEAN) Ledger delegate, MeterRegistry registry) {
        return new MeteredLedger(delegate, registry);
    }

    @Bean
    @Primary
    RuleEngine timedRuleEngine(@Qualifier(RULE_EVALUATOR_BEAN) RuleEngine delegate, MeterRegistry registry) {
        return new TimedRuleEngine(delegate, registry);
    }
}
