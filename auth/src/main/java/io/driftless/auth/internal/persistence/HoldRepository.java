package io.driftless.auth.internal.persistence;

import io.driftless.auth.api.HoldStatus;
import io.driftless.auth.spi.DanglingHold;
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

    /**
     * Every {@code ACTIVE} hold whose owning authorization is in a terminal state ({@code CAPTURED} /
     * {@code REVERSED} / {@code DECLINED}) — a dangling hold. Reconciliation's independent source of
     * truth for the hold-consistency check: empty for a consistent saga, non-empty the instant a hold
     * is left active past its authorization's terminal transition.
     */
    @Query(
            """
            SELECT new io.driftless.auth.spi.DanglingHold(
                h.id, h.authorizationId, h.accountId, h.currency, h.amountMinor, a.status)
            FROM HoldEntity h, AuthorizationEntity a
            WHERE h.authorizationId = a.id
              AND h.status = io.driftless.auth.api.HoldStatus.ACTIVE
              AND a.status IN (
                  io.driftless.auth.api.AuthorizationStatus.CAPTURED,
                  io.driftless.auth.api.AuthorizationStatus.REVERSED,
                  io.driftless.auth.api.AuthorizationStatus.DECLINED)
            """)
    List<DanglingHold> findDanglingHolds();

    /** Sum of ACTIVE hold amounts per {@code (account, currency)} — one side of the hold cross-foot. */
    @Query(
            """
            SELECT new io.driftless.auth.internal.persistence.AccountCurrencyMinor(
                h.accountId, h.currency, SUM(h.amountMinor))
            FROM HoldEntity h
            WHERE h.status = io.driftless.auth.api.HoldStatus.ACTIVE
            GROUP BY h.accountId, h.currency
            """)
    List<AccountCurrencyMinor> activeHoldSumsByAccount();
}
