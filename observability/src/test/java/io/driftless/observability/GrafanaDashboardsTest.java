package io.driftless.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the dashboards-as-code: the version-controlled Grafana JSON must be valid and must reference
 * the metric names the instrumentation actually exposes — so a rename can never silently break the
 * flagship "Zero Drift" view. The provisioning files must also be present so Grafana auto-loads
 * everything with no manual clicking.
 *
 * <p>Paths are resolved relative to the module directory (the Surefire working directory), walking up
 * to the repo's {@code deploy/} tree.
 */
class GrafanaDashboardsTest {

    private static final Path DEPLOY = Path.of("..", "deploy");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void zeroDriftDashboardIsValidAndReferencesTheDriftGauge() throws Exception {
        Path dashboard = DEPLOY.resolve("grafana/dashboards/zero-drift.json");
        assertThat(Files.exists(dashboard)).as("zero-drift dashboard exists").isTrue();

        String json = Files.readString(dashboard);
        JsonNode root = MAPPER.readTree(json); // throws if invalid JSON
        assertThat(root.get("title").asText()).isEqualTo("Driftless — Zero Drift");
        assertThat(root.get("uid").asText()).isEqualTo("driftless-zero-drift");
        assertThat(root.path("panels").isArray()).isTrue();

        // The flagship headline must read the EXACT exported gauge names (baseUnit suffix included),
        // so a future rename or a doubled-unit suffix can never silently break the dashboard panels.
        assertThat(json).contains("driftless_recon_drift_amount_minor_units");
        assertThat(json).contains("driftless_ledger_signed_sum_abs_minor_units");
        assertThat(json).contains("driftless_recon_run_passed");
        assertThat(json).contains("driftless_auth_compensating_reversals_total");
        // Guard against the historical mismatch regressing: the un-suffixed / doubled-suffix forms
        // must not appear as standalone selectors.
        assertThat(json).doesNotContain("driftless_recon_drift_amount)");
        assertThat(json).doesNotContain("driftless_ledger_signed_sum_abs_minor)");
        assertThat(json).doesNotContain("signed_sum_abs_minor_minor_units");
    }

    @Test
    void systemHealthDashboardIsValidAndReferencesTheRuleEngineP99() throws Exception {
        Path dashboard = DEPLOY.resolve("grafana/dashboards/system-health.json");
        assertThat(Files.exists(dashboard)).isTrue();

        String json = Files.readString(dashboard);
        MAPPER.readTree(json);
        assertThat(json).contains("driftless_rules_evaluation_seconds_bucket");
        assertThat(json).contains("histogram_quantile(0.99");
        assertThat(json).contains("driftless_ledger_postings_total");
    }

    @Test
    void provisioningFilesArePresent() {
        assertThat(Files.exists(DEPLOY.resolve("grafana/provisioning/datasources/prometheus.yml")))
                .isTrue();
        assertThat(Files.exists(DEPLOY.resolve("grafana/provisioning/dashboards/dashboards.yml")))
                .isTrue();
        assertThat(Files.exists(DEPLOY.resolve("prometheus/prometheus.yml"))).isTrue();
    }
}
