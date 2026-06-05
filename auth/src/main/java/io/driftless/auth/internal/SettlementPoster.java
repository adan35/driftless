package io.driftless.auth.internal;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Direction;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.PostingLine;
import io.driftless.ledger.api.PostingRequest;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Posts the balanced ledger transactions that move <em>posted</em> money for a capture or a reversal
 * of a capture. Each post is two legs that net to zero per currency, so the ledger accepts it and the
 * global sum of journal entries stays zero (invariant 1).
 *
 * <p><strong>Account model.</strong> The cardholder funding account (the {@code account} an
 * authorization is drawn against) carries the cardholder's available funds under the ledger's
 * debits-positive convention. A single per-currency {@code settlement} clearing account is the
 * counterparty. A capture moves funds out of the cardholder account into settlement:
 *
 * <pre>
 *   capture A:   CREDIT cardholder A   (posted -= A)   DEBIT settlement A
 *   reversal A:  DEBIT  cardholder A   (posted += A)   CREDIT settlement A   (the inverse, a NEW tx)
 * </pre>
 *
 * <p>The reversal of a capture is a <strong>new compensating transaction</strong> — the original
 * entries are never edited (immutability). Each post carries a stable idempotency key derived from the
 * authorization id, so a replayed capture/reverse re-uses the ledger's own idempotent {@code post} and
 * writes no extra rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SettlementPoster {

    private final Ledger ledger;

    /** Settle a capture: cardholder posted decreases by {@code amount}, settlement is the counterparty. */
    void postCapture(UUID authorizationId, AccountId cardholder, Money amount, Instant occurredAt) {
        AccountId settlement = ensureSettlementAccount(amount.currency());
        ledger.post(new PostingRequest(
                "auth-capture:" + authorizationId,
                occurredAt,
                "capture settlement for authorization " + authorizationId,
                List.of(
                        new PostingLine(cardholder, Direction.CREDIT, amount),
                        new PostingLine(settlement, Direction.DEBIT, amount))));
        log.info("posted capture settlement authorization={} amount={}", authorizationId, amount.amountMinor());
    }

    /** Post the inverse of a capture as a new compensating transaction; the original is untouched. */
    void postCaptureReversal(UUID authorizationId, AccountId cardholder, Money amount, Instant occurredAt) {
        AccountId settlement = ensureSettlementAccount(amount.currency());
        ledger.post(new PostingRequest(
                "auth-reverse:" + authorizationId,
                occurredAt,
                "reversal of capture for authorization " + authorizationId,
                List.of(
                        new PostingLine(cardholder, Direction.DEBIT, amount),
                        new PostingLine(settlement, Direction.CREDIT, amount))));
        log.info("posted capture reversal authorization={} amount={}", authorizationId, amount.amountMinor());
    }

    /**
     * Open (idempotently) the per-currency settlement clearing account. Its id is derived
     * deterministically from the currency so every capture/reversal in that currency settles against
     * the same account; {@code openAccount} is idempotent on the id, so concurrent first-uses are safe.
     */
    private AccountId ensureSettlementAccount(Currency currency) {
        AccountId id = settlementAccountId(currency);
        ledger.openAccount(new Account(id, AccountType.ASSET, currency, "settlement-" + currency.getCurrencyCode()));
        return id;
    }

    static AccountId settlementAccountId(Currency currency) {
        return AccountId.of(UUID.nameUUIDFromBytes(("driftless-settlement-" + currency.getCurrencyCode())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
