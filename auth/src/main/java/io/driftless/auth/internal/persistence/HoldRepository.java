package io.driftless.auth.internal.persistence;

import io.driftless.auth.api.HoldStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code hold} — the rows feeding the ledger {@code HoldView}. */
public interface HoldRepository extends JpaRepository<HoldEntity, UUID> {

    /**
     * Total of currently-ACTIVE holds against an account in a currency, in minor units — the figure
     * the ledger subtracts from posted to compute available. Returns {@code 0} when none are active.
     */
    @Query(
            """
            SELECT COALESCE(SUM(h.amountMinor), 0)
            FROM HoldEntity h
            WHERE h.accountId = :accountId AND h.currency = :currency AND h.status = io.driftless.auth.api.HoldStatus.ACTIVE
            """)
    long activeHoldTotalMinor(@Param("accountId") UUID accountId, @Param("currency") String currency);

    /** Holds for one authorization in a given status (the ACTIVE ones to release on capture/reversal). */
    List<HoldEntity> findByAuthorizationIdAndStatus(UUID authorizationId, HoldStatus status);
}
