package io.driftless.auth.internal;

import io.driftless.auth.api.HoldStatus;
import io.driftless.auth.internal.persistence.HoldEntity;
import io.driftless.auth.internal.persistence.HoldRepository;
import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The hold lifecycle: place an ACTIVE hold (reducing available, leaving posted unchanged) and release
 * the active holds of an authorization (restoring available) on capture or reversal.
 *
 * <p>Holds are never deleted — release is a status flip — so the audit trail stays intact
 * (immutability). Releasing is idempotent: an already-RELEASED hold is left untouched, so a replayed
 * capture/reverse or a recovery re-drive releases each hold exactly once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class Holds {

    private final HoldRepository holds;

    /** Place a fresh ACTIVE hold for {@code amount} against {@code account}; available drops at once. */
    void place(UUID authorizationId, AccountId account, Money amount, Instant now) {
        HoldEntity hold = new HoldEntity(
                UUID.randomUUID(),
                authorizationId,
                account.value(),
                amount.amountMinor(),
                amount.currency().getCurrencyCode(),
                now);
        holds.save(hold);
        log.info(
                "hold placed authorization={} account={} amount={} {}",
                authorizationId,
                account,
                amount.amountMinor(),
                amount.currency().getCurrencyCode());
    }

    /** Release every ACTIVE hold of an authorization; available is restored. Idempotent. */
    void releaseActive(UUID authorizationId, Instant now) {
        List<HoldEntity> active = holds.findByAuthorizationIdAndStatus(authorizationId, HoldStatus.ACTIVE);
        for (HoldEntity hold : active) {
            hold.release(now);
            holds.save(hold);
        }
        if (!active.isEmpty()) {
            log.info("hold released authorization={} count={}", authorizationId, active.size());
        }
    }
}
