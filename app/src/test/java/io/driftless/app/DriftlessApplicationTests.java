package io.driftless.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.idempotency.api.IdempotencyGuard;
import io.driftless.ledger.api.Ledger;
import io.driftless.observability.metrics.MeteredLedger;
import io.driftless.observability.metrics.TimedRuleEngine;
import io.driftless.outbox.api.OutboxWriter;
import io.driftless.recon.api.ReconciliationResult;
import io.driftless.recon.internal.ReconciliationService;
import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.RuleEngine;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.tokens.api.TokenService;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the full modular monolith against a real Testcontainers Postgres and asserts the Spec 08
 * deliverables end-to-end:
 *
 * <ul>
 *   <li>every module's beans are wired (ledger / idempotency / outbox / rules / tokens / auth saga /
 *       reconciliation) and a real datasource is present;
 *   <li>every module's Flyway timeline has been applied — all module tables and their distinct
 *       history tables exist;
 *   <li>the Prometheus scrape (what {@code /actuator/prometheus} serves) exposes the {@code
 *       driftless_*} custom metrics with sensible names, including the zero-drift gauge, the
 *       rule-engine latency histogram and the compensating-reversal counter;
 *   <li>a real reconciliation run drives {@code driftless_recon_drift_amount} to {@code 0} and the
 *       pass gauge to {@code 1}.
 * </ul>
 */
@SpringBootTest
@Import(DriftlessApplicationTests.PostgresContainerConfig.class)
class DriftlessApplicationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfig {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private PrometheusMeterRegistry prometheusRegistry;

    @Test
    void contextLoads() {}

    @Test
    void browsableOpenApiContractIsServedFromTheClasspath() {
        // The committed deploy/openapi/openapi.yaml is copied onto the classpath at build time and
        // served statically at /openapi.yaml; Swagger UI renders it at /swagger-ui/index.html.
        assertThat(new org.springframework.core.io.ClassPathResource("static/openapi.yaml").exists())
                .as("OpenAPI contract is on the classpath (served at /openapi.yaml)")
                .isTrue();
        assertThat(new org.springframework.core.io.ClassPathResource("static/swagger-ui/index.html").exists())
                .as("Swagger UI page is present (served at /swagger-ui/index.html)")
                .isTrue();
    }

    @Test
    void everyModuleBeanAndARealDatasourceAreWired() {
        assertThat(context.getBean(Ledger.class)).isInstanceOf(MeteredLedger.class);
        assertThat(context.getBean(RuleEngine.class)).isInstanceOf(TimedRuleEngine.class);
        assertThat(context.getBean(IdempotencyGuard.class)).isNotNull();
        assertThat(context.getBean(OutboxWriter.class)).isNotNull();
        assertThat(context.getBean(TokenService.class)).isNotNull();
        assertThat(context.getBean(ReconciliationService.class)).isNotNull();
        // The Prometheus scrape endpoint is auto-configured (registry present + exposure includes it).
        assertThat(context.containsBean("prometheusEndpoint")).isTrue();
        assertThat(dataSource).isNotNull();
    }

    @Test
    void everyModuleFlywayTimelineIsApplied() throws Exception {
        List<String> required = List.of(
                "account",
                "journal_entry",
                "idempotency_record",
                "outbox_event",
                "token",
                "authorization",
                "hold",
                "reconciliation_result",
                "flyway_schema_history",
                "flyway_schema_history_idempotency",
                "flyway_schema_history_tokens",
                "flyway_schema_history_auth",
                "flyway_schema_history_recon");

        List<String> present = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet tables = connection
                        .getMetaData()
                        .getTables(connection.getCatalog(), "public", "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                present.add(tables.getString("TABLE_NAME").toLowerCase());
            }
        }
        assertThat(present)
                .as("all module tables + per-module history tables migrated")
                .containsAll(required);
    }

    @Test
    void prometheusScrapeExposesDriftlessMetrics() {
        ruleEngine.evaluate(new AuthContext(
                io.driftless.common.id.AccountId.newId(),
                io.driftless.common.money.Money.of(100L, "USD"),
                "5411",
                "merchant-1",
                Instant.now(),
                io.driftless.common.money.Money.of(100_000L, "USD"),
                VelocitySnapshot.empty(io.driftless.common.money.Money.of(0L, "USD"))));

        String body = prometheusRegistry.scrape();
        assertThat(body)
                .contains("driftless_recon_drift_amount_minor_units")
                .contains("driftless_ledger_entry_count")
                .contains("driftless_ledger_signed_sum_abs_minor_units")
                .contains("driftless_outbox_pending_depth")
                .contains("driftless_auth_dangling_partner_reverse")
                .contains("driftless_auth_outstanding_partner_obligations")
                .contains("driftless_auth_compensating_reversals_total")
                .contains("driftless_rules_evaluation_seconds")
                .contains("driftless_rules_evaluation_seconds_bucket")
                .contains("driftless_rules_decisions_total");
        assertThat(body).contains("jvm_memory_used_bytes");
    }

    /**
     * Closes the dashboard / instrumentation drift gap (the "No data" defect): boots the monolith,
     * scrapes {@code /actuator/prometheus} and asserts that EVERY {@code driftless_*} metric name the
     * provisioned Grafana dashboards query is actually exported. Parses the panel PromQL straight out
     * of the version-controlled JSON, so a future meter rename (or a doubled base-unit suffix) that
     * leaves a panel selecting a non-existent series fails this test instead of silently rendering
     * "No data". Non-{@code driftless_*} series (e.g. {@code http_server_requests_*}, partner targets)
     * are out of scope here — they are owned by other targets and asserted elsewhere.
     */
    @Test
    void everyDriftlessDashboardMetricIsActuallyExported() throws Exception {
        // A rule evaluation makes the rule-engine histogram/decision series materialise before scrape.
        ruleEngine.evaluate(new AuthContext(
                io.driftless.common.id.AccountId.newId(),
                io.driftless.common.money.Money.of(100L, "USD"),
                "5411",
                "merchant-1",
                Instant.now(),
                io.driftless.common.money.Money.of(100_000L, "USD"),
                VelocitySnapshot.empty(io.driftless.common.money.Money.of(0L, "USD"))));

        String body = prometheusRegistry.scrape();
        Set<String> referenced = new LinkedHashSet<>();
        Path dashboards = Path.of("..", "deploy", "grafana", "dashboards");
        for (String file : List.of("zero-drift.json", "system-health.json")) {
            referenced.addAll(driftlessMetricsReferencedBy(dashboards.resolve(file)));
        }

        assertThat(referenced)
                .as("dashboards must query the zero-drift headline series by their exact exported names")
                .contains("driftless_recon_drift_amount_minor_units", "driftless_ledger_signed_sum_abs_minor_units");
        for (String metric : referenced) {
            assertThat(body)
                    .as("dashboard metric %s must be present in /actuator/prometheus", metric)
                    .contains(metric);
        }
    }

    /** Extract the distinct {@code driftless_*} metric names every panel target's PromQL references. */
    private static Set<String> driftlessMetricsReferencedBy(Path dashboard) throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(dashboard));
        Pattern metric = Pattern.compile("driftless_[a-z0-9_]+");
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode panel : root.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                String expr = target.path("expr").asText("");
                Matcher matcher = metric.matcher(expr);
                while (matcher.find()) {
                    names.add(matcher.group());
                }
            }
        }
        return names;
    }

    @Test
    void reconciliationRunDrivesTheZeroDriftGaugeToZero() {
        ReconciliationResult result = reconciliationService.run();
        assertThat(result.passed()).isTrue();
        assertThat(result.totalDriftMinor()).isZero();

        String body = prometheusRegistry.scrape();
        assertThat(scrapeValue(body, "driftless_recon_drift_amount")).isZero();
        assertThat(scrapeValue(body, "driftless_recon_run_passed")).isEqualTo(1.0);
    }

    /**
     * QA honesty proof (Spec 08 gate): the zero-drift gauge must go RED if drift ever exists. We can
     * not honestly forge an unbalanced ledger (the V2 immutability/balance trigger forbids it), so we
     * exercise the exact production wiring that paints the dashboard: a FAILED {@link
     * ReconciliationResult} carrying nonzero drift, published as the real Spring application event the
     * {@code ReconciliationService} emits, must flip the live {@code /actuator/prometheus} gauges to
     * {@code drift>0} and {@code passed=0}. This proves the {@code ReconMetrics} {@code @EventListener}
     * is genuinely bound in the booted monolith — not just unit-wired — so the headline panel is honest.
     * It then runs a real (passing) reconciliation to restore the gauges to green, leaving no ordering
     * dependency for the other tests.
     */
    @Test
    void aFailedReconciliationEventTurnsTheZeroDriftGaugeRedEndToEnd() {
        ReconciliationResult drifted = new ReconciliationResult(
                java.util.UUID.randomUUID(),
                Instant.now(),
                false,
                7350L,
                List.of(
                        io.driftless.recon.api.CheckResult.fail(
                                io.driftless.recon.api.ReconCheck.PER_TRANSACTION,
                                7350L,
                                "2 unbalanced transaction(s)"),
                        io.driftless.recon.api.CheckResult.fail(
                                io.driftless.recon.api.ReconCheck.GLOBAL_ZERO,
                                7350L,
                                "ledger-wide signed sum non-zero")),
                List.of());

        context.publishEvent(drifted);

        String redBody = prometheusRegistry.scrape();
        assertThat(scrapeValue(redBody, "driftless_recon_drift_amount"))
                .as("drift gauge must reflect real drift (would show red on the dashboard)")
                .isEqualTo(7350.0);
        assertThat(scrapeValue(redBody, "driftless_recon_run_passed"))
                .as("pass gauge must read 0 on a failed run")
                .isZero();

        // Restore to a genuine green state so test ordering can not affect the zero-drift assertion.
        ReconciliationResult restored = reconciliationService.run();
        assertThat(restored.passed()).isTrue();
        String greenBody = prometheusRegistry.scrape();
        assertThat(scrapeValue(greenBody, "driftless_recon_drift_amount")).isZero();
        assertThat(scrapeValue(greenBody, "driftless_recon_run_passed")).isEqualTo(1.0);
    }

    private static double scrapeValue(String prometheusBody, String metric) {
        for (String line : prometheusBody.split("\n")) {
            if (line.startsWith(metric) && !line.startsWith("# ")) {
                String value = line.substring(line.lastIndexOf(' ') + 1).trim();
                return Double.parseDouble(value);
            }
        }
        throw new AssertionError("metric not found in exposition: " + metric);
    }
}
