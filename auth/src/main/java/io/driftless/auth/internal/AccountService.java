package io.driftless.auth.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.auth.api.UnknownAccountException;
import io.driftless.common.id.AccountId;
import io.driftless.common.id.TxId;
import io.driftless.common.money.CurrencyMismatchException;
import io.driftless.common.money.Money;
import io.driftless.idempotency.api.IdempotencyGuard;
import io.driftless.idempotency.api.IdempotentResult;
import io.driftless.idempotency.api.StoredResult;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Direction;
import io.driftless.ledger.api.EntryCursor;
import io.driftless.ledger.api.EntryPage;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.PostingLine;
import io.driftless.ledger.api.PostingRequest;
import io.driftless.ledger.api.PostingResult;
import io.driftless.ledger.api.Transaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The application service behind the public account-lifecycle + funding REST surface.
 *
 * <p>It is a thin, idempotent orchestration over the frozen {@code io.driftless.ledger.api} — it owns
 * no persistence of its own (the ledger is the single source of truth for accounts, balances and
 * entries) and depends on nothing but {@code ledger}, {@code common} and the Spec 02
 * {@link IdempotencyGuard}.
 *
 * <p><strong>Funding account model (debit-positive convention).</strong> Driftless derives a posted
 * balance as {@code Σ(DEBIT) − Σ(CREDIT)} for every account (see {@code JournalEntryRepository}), and
 * the auth saga authorizes against {@code available = posted − holds}. <em>Loading</em> funds onto an
 * account must therefore raise its posted balance — a <strong>DEBIT to the target</strong> account
 * and a balancing <strong>CREDIT to a per-currency system funding source</strong> (an {@code ASSET}
 * clearing account opened on demand). This is the inverse of a capture and matches the funding the
 * demo/load generator already uses; the colloquial "credit the account with funds" is realized as a
 * ledger DEBIT under the debit-positive convention. The post is always two legs that net to zero, so
 * the global {@code Σ journal entries == 0} invariant holds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final String OPEN_SCOPE = "accounts.open:";
    private static final String FUND_SCOPE = "accounts.fund:";

    private final IdempotencyGuard guard;
    private final ObjectMapper objectMapper;
    private final Ledger ledger;
    private final Clock clock;

    /**
     * Open a fresh ledger account under the caller's idempotency key. A replay returns the originally
     * created account (the guard stores the minted id), so a retried POST never opens a second
     * account.
     */
    public Account open(OpenAccountCommand cmd, String idempotencyKey) {
        String key = OPEN_SCOPE + idempotencyKey;
        String requestHash =
                hash("OPEN|%s|%s|%s".formatted(cmd.type(), cmd.currency().getCurrencyCode(), cmd.name()));
        IdempotentResult<UUID> result = guard.execute(key, requestHash, () -> {
            AccountId id = AccountId.newId();
            Account opened = ledger.openAccount(new Account(id, cmd.type(), cmd.currency(), cmd.name()));
            log.info("opened account {} type={} currency={} via REST", id, cmd.type(), cmd.currency());
            return storedId(opened.id().value());
        });
        AccountId id = AccountId.of(result.value());
        return ledger.findAccount(id).orElseThrow(() -> new UnknownAccountException(id));
    }

    /** Account details, or {@link UnknownAccountException} ({@code 404}) when unknown. */
    public Account find(AccountId account) {
        return ledger.findAccount(account).orElseThrow(() -> new UnknownAccountException(account));
    }

    /**
     * Fund (load) an account with a balanced ledger post under the caller's idempotency key. Rejects
     * an unknown account ({@link UnknownAccountException}) or a currency that does not match the
     * account ({@link CurrencyMismatchException}) before writing anything; a replay re-uses the
     * ledger's own idempotent post and writes no new rows.
     */
    public FundingReceipt fund(AccountId account, Money amount, String idempotencyKey) {
        Account target = find(account);
        Currency accountCurrency = target.currency();
        if (!amount.currency().equals(accountCurrency)) {
            throw new CurrencyMismatchException(accountCurrency, amount.currency());
        }

        String key = FUND_SCOPE + idempotencyKey;
        String requestHash = hash("FUND|%s|%d|%s"
                .formatted(account, amount.amountMinor(), amount.currency().getCurrencyCode()));
        IdempotentResult<UUID> result = guard.execute(
                key,
                requestHash,
                () -> storedId(postFunding(account, amount).transactionId().value()));

        Transaction tx = ledger.findTransaction(TxId.of(result.value()));
        log.info(
                "funded account {} amount={} {} transaction={}",
                account,
                amount.amountMinor(),
                amount.currency().getCurrencyCode(),
                tx.id().value());
        return new FundingReceipt(tx.id().value(), tx.postedAt());
    }

    /** A keyset (seek) page of the account's entries (statement), oldest first. */
    public EntryPage statement(AccountId account, EntryCursor cursor, int limit) {
        find(account); // 404 for an unknown account, consistent with the other reads
        return ledger.entriesAfter(account, cursor, limit);
    }

    /** Post the balanced funding transaction: DEBIT the target, CREDIT the per-currency funding source. */
    private PostingResult postFunding(AccountId account, Money amount) {
        Currency currency = amount.currency();
        AccountId source = ensureFundingSource(currency);
        Instant occurredAt = clock.instant();
        return ledger.post(new PostingRequest(
                "account-funding:" + account + ":" + amount.amountMinor() + ":" + currency.getCurrencyCode(),
                occurredAt,
                "funding load for account " + account,
                java.util.List.of(
                        new PostingLine(account, Direction.DEBIT, amount),
                        new PostingLine(source, Direction.CREDIT, amount))));
    }

    /**
     * Open (idempotently) the per-currency funding source: a deterministic {@code ASSET} clearing
     * account that represents value injected into the system. {@code openAccount} is idempotent on the
     * id, so concurrent first-uses are safe.
     */
    private AccountId ensureFundingSource(Currency currency) {
        AccountId id = fundingSourceAccountId(currency);
        ledger.openAccount(
                new Account(id, AccountType.ASSET, currency, "funding-source-" + currency.getCurrencyCode()));
        return id;
    }

    /** Deterministic per-currency funding-source account id (stable across replays and processes). */
    static AccountId fundingSourceAccountId(Currency currency) {
        return AccountId.of(UUID.nameUUIDFromBytes(
                ("driftless-funding-source-" + currency.getCurrencyCode()).getBytes(StandardCharsets.UTF_8)));
    }

    private StoredResult<UUID> storedId(UUID id) {
        try {
            return StoredResult.of(id, objectMapper.writeValueAsString(id));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize account/transaction id", e);
        }
    }

    private String hash(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
