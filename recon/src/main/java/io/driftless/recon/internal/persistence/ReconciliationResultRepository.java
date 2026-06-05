package io.driftless.recon.internal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code reconciliation_result} — the persisted balance-proof runs. */
public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResultEntity, UUID> {

    /** The most recent run, by business time, for the dashboard's "latest" read. */
    Optional<ReconciliationResultEntity> findFirstByOrderByRanAtDescIdDesc();
}
