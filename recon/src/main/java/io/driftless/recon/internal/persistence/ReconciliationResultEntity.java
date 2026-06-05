package io.driftless.recon.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * JPA row for {@code reconciliation_result} — one persisted balance-proof run, the evidence Spec 08's
 * dashboard renders and the input to an RCA report.
 *
 * <p>The per-check outcomes and the offending accounts/transactions are stored as canonical JSON
 * (serialized by the service from the api records) so the structured detail survives without a
 * sprawling relational schema. The row is written once and never mutated — a new run is a new row.
 *
 * <p>Implements {@link Persistable} with a caller-assigned UUID id so a fresh {@code save()} issues a
 * plain {@code INSERT} rather than a {@code merge}-with-preceding-SELECT.
 */
@Entity
@Table(name = "reconciliation_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationResultEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ran_at", nullable = false, updatable = false)
    private Instant ranAt;

    @Column(name = "passed", nullable = false, updatable = false)
    private boolean passed;

    @Column(name = "total_drift_minor", nullable = false, updatable = false)
    private long totalDriftMinor;

    @Column(name = "checks_json", nullable = false, updatable = false)
    private String checksJson;

    @Column(name = "offenders_json", nullable = false, updatable = false)
    private String offendersJson;

    @Column(name = "summary", nullable = false, updatable = false, length = 512)
    private String summary;

    public ReconciliationResultEntity(
            UUID id,
            Instant ranAt,
            boolean passed,
            long totalDriftMinor,
            String checksJson,
            String offendersJson,
            String summary) {
        this.id = id;
        this.ranAt = ranAt;
        this.passed = passed;
        this.totalDriftMinor = totalDriftMinor;
        this.checksJson = checksJson;
        this.offendersJson = offendersJson;
        this.summary = summary;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        // Caller-assigned UUID, written exactly once: always a fresh INSERT.
        return true;
    }
}
