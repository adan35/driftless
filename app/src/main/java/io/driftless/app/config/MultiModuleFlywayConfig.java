package io.driftless.app.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composes every module's Flyway history into the one monolith datasource at startup.
 *
 * <p>Each feature module ships an <em>independent</em> migration timeline that starts at {@code V1}
 * under its own sibling location, so the modules never collide on version numbers:
 *
 * <ul>
 *   <li>{@code db/migration} — ledger (the Boot auto-configured Flyway; default history table)
 *   <li>{@code db/migration_idempotency} — idempotency guard + outbox
 *   <li>{@code db/migration_tokens} — tokenization lifecycle
 *   <li>{@code db/migration_auth} — authorization saga (authorizations + holds)
 *   <li>{@code db/migration_recon} — reconciliation results
 * </ul>
 *
 * <p>The auto-configured Flyway migrates the ledger (its default location); this strategy then runs
 * each remaining timeline against the same datasource into a <strong>distinct history table</strong>
 * ({@code flyway_schema_history_<module>}). {@code baselineOnMigrate} with {@code baselineVersion=0}
 * lets each independent history baseline over the already-populated {@code public} schema while still
 * applying its own {@code V1}. This is the runtime counterpart of the per-module test migration
 * strategies, so the booted monolith has every module's tables — which {@code ddl-auto=validate} then
 * checks Hibernate's mappings against.
 */
@Configuration
public class MultiModuleFlywayConfig {

    private final DataSource dataSource;

    public MultiModuleFlywayConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    FlywayMigrationStrategy multiModuleFlywayMigrationStrategy() {
        return ledgerFlyway -> {
            ledgerFlyway.migrate();
            migrateModule("classpath:db/migration_idempotency", "flyway_schema_history_idempotency");
            migrateModule("classpath:db/migration_tokens", "flyway_schema_history_tokens");
            migrateModule("classpath:db/migration_auth", "flyway_schema_history_auth");
            migrateModule("classpath:db/migration_recon", "flyway_schema_history_recon");
        };
    }

    private void migrateModule(String location, String historyTable) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
