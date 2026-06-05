package io.driftless.auth.internal;

import io.driftless.auth.internal.persistence.AccountCurrencyMinor;
import io.driftless.auth.internal.persistence.AuthorizationRepository;
import io.driftless.auth.internal.persistence.HoldRepository;
import io.driftless.auth.spi.DanglingHold;
import io.driftless.auth.spi.HoldBalanceMismatch;
import io.driftless.auth.spi.HoldReconView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Spec 03 implementation of the read-only {@link HoldReconView} SPI. It reads the committed
 * {@code hold} and {@code authorization} rows this module owns and exposes them to reconciliation as
 * an <strong>independent</strong> check of hold consistency — derived from the authorizations' own
 * statuses, not from the ledger's {@code balanceOf}.
 *
 * <p>Strictly read-only: it never edits a hold or authorization.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldReconViewImpl implements HoldReconView {

    private final HoldRepository holds;
    private final AuthorizationRepository authorizations;

    @Override
    @Transactional(readOnly = true)
    public List<DanglingHold> findDanglingHolds() {
        List<DanglingHold> dangling = holds.findDanglingHolds();
        if (!dangling.isEmpty()) {
            log.warn("recon found {} dangling ACTIVE hold(s) on terminal authorizations", dangling.size());
        }
        return dangling;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HoldBalanceMismatch> findHoldBalanceMismatches() {
        Map<AccountCurrency, Long> activeHolds = index(holds.activeHoldSumsByAccount());
        Map<AccountCurrency, Long> authorized = index(authorizations.authorizedAmountSumsByAccount());

        List<HoldBalanceMismatch> mismatches = new ArrayList<>();
        for (AccountCurrency key : union(activeHolds, authorized)) {
            long activeHoldMinor = activeHolds.getOrDefault(key, 0L);
            long authorizedMinor = authorized.getOrDefault(key, 0L);
            if (activeHoldMinor != authorizedMinor) {
                mismatches.add(
                        new HoldBalanceMismatch(key.accountId(), key.currency(), activeHoldMinor, authorizedMinor));
            }
        }
        if (!mismatches.isEmpty()) {
            log.warn(
                    "recon found {} account(s) where ACTIVE holds do not cross-foot AUTHORIZED amounts",
                    mismatches.size());
        }
        return mismatches;
    }

    private static Map<AccountCurrency, Long> index(List<AccountCurrencyMinor> rows) {
        Map<AccountCurrency, Long> byKey = new LinkedHashMap<>();
        for (AccountCurrencyMinor row : rows) {
            byKey.put(new AccountCurrency(row.accountId(), row.currency()), row.totalMinor());
        }
        return byKey;
    }

    private static List<AccountCurrency> union(Map<AccountCurrency, Long> a, Map<AccountCurrency, Long> b) {
        List<AccountCurrency> keys = new ArrayList<>(a.keySet());
        for (AccountCurrency key : b.keySet()) {
            if (!a.containsKey(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private record AccountCurrency(UUID accountId, String currency) {}
}
