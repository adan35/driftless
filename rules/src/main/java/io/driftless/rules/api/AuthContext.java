package io.driftless.rules.api;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * The full input to a single authorization decision, assembled by the auth saga (Spec 03) and passed
 * to {@link RuleEngine#evaluate} on the hot path.
 *
 * <p>Everything the engine needs is carried here so evaluation does <strong>no I/O</strong>: the
 * {@code availableBalance} is read from the ledger by the saga (not by the engine), and the {@code
 * velocity} snapshot is read from the in-memory velocity store. Two contexts that are {@code equals}
 * always yield the same {@link RuleResult} — evaluation is a pure function of this record and the
 * in-memory compiled rules.
 *
 * @param account the account the authorization is drawn against
 * @param amount the requested authorization amount (minor units; positive)
 * @param mcc merchant category code (ISO-18245, 4 digits as text)
 * @param merchantId opaque merchant identifier
 * @param requestedAt business time of the request, taken from the caller's injected {@code Clock}
 * @param availableBalance the account's available balance, supplied by the saga from the ledger
 * @param velocity recent-activity snapshot for the account's rolling window
 */
public record AuthContext(
        AccountId account,
        Money amount,
        String mcc,
        String merchantId,
        Instant requestedAt,
        Money availableBalance,
        VelocitySnapshot velocity) {

    public AuthContext {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(mcc, "mcc");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(availableBalance, "availableBalance");
        Objects.requireNonNull(velocity, "velocity");
    }
}
