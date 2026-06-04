package io.driftless.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.Decision;
import io.driftless.rules.api.RuleEngine;
import io.driftless.rules.api.VelocitySnapshot;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Proves the acceptance criterion "{@code evaluate} performs no database I/O" by construction: the
 * whole rule-engine module boots and decides authorizations with <strong>no {@link DataSource} in the
 * context at all</strong>. There is no repository, no JDBC, nothing the fault-injection harness (Spec
 * 07) could stall on the decision path.
 */
@SpringBootTest(classes = RulesTestApplication.class)
class NoDatabaseOnHotPathTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RuleEngine ruleEngine;

    @Test
    void theEngineContextHasNoDataSource() {
        assertThat(context.getBeanNamesForType(DataSource.class))
                .as("the rule engine hot path must not depend on any datasource")
                .isEmpty();
    }

    @Test
    void theEngineEvaluatesWithNoDatabasePresent() {
        AuthContext clean = context();
        assertThat(ruleEngine.evaluate(clean).decision()).isEqualTo(Decision.APPROVE);
    }

    private static AuthContext context() {
        return new AuthContext(
                RuleFixtures.ACCOUNT,
                RuleFixtures.usd(5_000),
                RuleFixtures.GROCERIES_MCC,
                "merchant-1",
                RuleFixtures.AT,
                RuleFixtures.usd(100_000),
                new VelocitySnapshot(0, RuleFixtures.usd(0)));
    }
}
