package io.driftless.ledger.internal;

import io.driftless.common.id.AccountId;
import io.driftless.common.id.TxId;
import io.driftless.common.money.Money;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.Balance;
import io.driftless.ledger.api.BalanceInvariantViolation;
import io.driftless.ledger.api.Direction;
import io.driftless.ledger.api.EntryCursor;
import io.driftless.ledger.api.EntryPage;
import io.driftless.ledger.api.JournalEntry;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.Page;
import io.driftless.ledger.api.PostingLine;
import io.driftless.ledger.api.PostingRequest;
import io.driftless.ledger.api.PostingResult;
import io.driftless.ledger.api.Transaction;
import io.driftless.ledger.internal.error.AccountCurrencyMismatch;
import io.driftless.ledger.internal.error.AccountNotFound;
import io.driftless.ledger.internal.error.TransactionNotFound;
import io.driftless.ledger.internal.persistence.AccountEntity;
import io.driftless.ledger.internal.persistence.AccountRepository;
import io.driftless.ledger.internal.persistence.JournalEntryEntity;
import io.driftless.ledger.internal.persistence.JournalEntryRepository;
import io.driftless.ledger.internal.persistence.TransactionEntity;
import io.driftless.ledger.internal.persistence.TransactionRepository;
import io.driftless.ledger.spi.HoldView;
import java.time.Clock;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Spec 01 implementation of the frozen {@link Ledger} contract: an append-only, double-entry
 * journal with balance-by-summation.
 *
 * <p>Correctness rules enforced here:
 *
 * <ul>
 *   <li><b>Balance</b> — a post is rejected with {@link BalanceInvariantViolation} unless, per
 *       currency, {@code SUM(DEBIT) == SUM(CREDIT)}; nothing is written on rejection.
 *   <li><b>Immutability</b> — only inserts; no {@code UPDATE}/{@code DELETE} path exists against
 *       {@code journal_entry}. Corrections are new compensating posts.
 *   <li><b>Idempotency</b> — a replayed {@code idempotencyKey} returns the original result with
 *       {@code replayed == true} and writes no new rows (lookup-first, with the unique constraint as
 *       the concurrent backstop).
 * </ul>
 *
 * <p>{@code occurredAt} is supplied by the caller (business time); {@code postedAt} is read from the
 * injected {@link Clock}. Balance is never stored — it is summed over entries on read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService implements Ledger {

    /** Server-side cap on a single keyset page, mirrored by the statement REST endpoint. */
    static final int MAX_ENTRIES_PER_PAGE = 200;

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final JournalEntryRepository entries;
    private final HoldView holdView;
    private final Clock clock;

    @Override
    @Transactional
    public Account openAccount(Account account) {
        UUID id = account.id().value();
        // Idempotent on the caller-supplied id: re-opening returns the existing account untouched.
        Optional<AccountEntity> existing = accounts.findById(id);
        if (existing.isPresent()) {
            log.debug("openAccount {} already exists; returning existing", account.id());
            return LedgerMapper.toApi(existing.get());
        }
        AccountEntity saved = accounts.save(new AccountEntity(
                id, account.type(), account.currency().getCurrencyCode(), account.name(), clock.instant()));
        log.info("opened account {} type={} currency={}", account.id(), account.type(), account.currency());
        return LedgerMapper.toApi(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findAccount(AccountId account) {
        return accounts.findById(account.value()).map(LedgerMapper::toApi);
    }

    @Override
    @Transactional
    public PostingResult post(PostingRequest request) {
        // 1. Replay fast-path: an already-posted key returns the original result, no new rows.
        Optional<TransactionEntity> replay = transactions.findByIdempotencyKey(request.idempotencyKey());
        if (replay.isPresent()) {
            TransactionEntity tx = replay.get();
            log.info("post replay key={} txId={} replayed=true", request.idempotencyKey(), tx.getId());
            return new PostingResult(TxId.of(tx.getId()), tx.getPostedAt(), true);
        }

        // 2. Validate the request as a whole before writing anything (balance + currency match).
        validateBalanced(request);

        // 3. Assemble and persist the balanced batch atomically.
        TransactionEntity tx = new TransactionEntity(
                UUID.randomUUID(),
                request.idempotencyKey(),
                request.occurredAt(),
                clock.instant(),
                request.description());
        int sequenceNo = 0;
        for (PostingLine line : request.lines()) {
            AccountEntity account = requireAccount(line.account());
            requireMatchingCurrency(line, account);
            tx.addEntry(new JournalEntryEntity(
                    UUID.randomUUID(),
                    tx,
                    account.getId(),
                    line.direction(),
                    line.amount().amountMinor(),
                    line.amount().currency().getCurrencyCode(),
                    sequenceNo++));
        }

        try {
            TransactionEntity saved = transactions.saveAndFlush(tx);
            log.info(
                    "post committed key={} txId={} lines={} balanced=true replayed=false",
                    request.idempotencyKey(),
                    saved.getId(),
                    saved.getEntries().size());
            return new PostingResult(TxId.of(saved.getId()), saved.getPostedAt(), false);
        } catch (DataIntegrityViolationException race) {
            // Concurrent replay won the unique-key race: return the winner's original result.
            TransactionEntity winner =
                    transactions.findByIdempotencyKey(request.idempotencyKey()).orElseThrow(() -> race);
            log.info(
                    "post lost idempotency race key={} winningTxId={} replayed=true",
                    request.idempotencyKey(),
                    winner.getId());
            return new PostingResult(TxId.of(winner.getId()), winner.getPostedAt(), true);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Balance balanceOf(AccountId account) {
        AccountEntity entity = requireAccount(account);
        Currency currency = Currency.getInstance(entity.getCurrency());
        long postedMinor = entries.netPostedMinor(account.value(), entity.getCurrency());
        Money posted = Money.of(postedMinor, currency);
        Money available = posted.minus(holdView.activeHoldTotal(account, currency));
        log.debug("balanceOf {} posted={} available={}", account, posted.amountMinor(), available.amountMinor());
        return new Balance(account, currency, posted, available);
    }

    @Override
    @Transactional(readOnly = true)
    public Transaction findTransaction(TxId id) {
        return transactions
                .findWithEntriesById(id.value())
                .map(LedgerMapper::toApi)
                .orElseThrow(() -> new TransactionNotFound(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalEntry> entriesFor(AccountId account, Page page) {
        requireAccount(account);
        List<JournalEntryEntity> rows =
                entries.findPageForAccount(account.value(), PageRequest.of(page.number(), page.size()));
        return LedgerMapper.toApiEntries(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public EntryPage entriesAfter(AccountId account, EntryCursor cursor, int limit) {
        requireAccount(account);
        int capped = Math.max(1, Math.min(limit, MAX_ENTRIES_PER_PAGE));
        EntryCursor from = cursor == null ? EntryCursor.START : cursor;
        List<JournalEntryEntity> rows =
                entries.findEntriesAfter(account.value(), from.afterSequence(), PageRequest.of(0, capped));
        List<JournalEntry> mapped = LedgerMapper.toApiEntries(rows);
        // A full page implies there may be more; expose the last row's stable global sequence as the
        // next cursor. A short page is the end of the currently visible stream, so no cursor. Paging
        // is gap-free and duplicate-free for entries committed in entry_seq order; because Postgres
        // sequences are non-transactional, a lower-seq row that commits after a higher-seq row already
        // paged past can be transiently omitted — never lost or duplicated. A fresh read from START
        // returns the complete set once writers commit.
        Optional<EntryCursor> next = rows.size() == capped
                ? Optional.of(EntryCursor.after(rows.get(rows.size() - 1).getEntrySeq()))
                : Optional.empty();
        log.debug("entriesAfter {} from={} limit={} returned={}", account, from.afterSequence(), capped, rows.size());
        return new EntryPage(mapped, next);
    }

    /**
     * Rejects the request unless debits net to credits in every currency. Currency-mismatch checks
     * against the accounts happen during assembly; this method is purely the balance invariant and
     * throws {@link BalanceInvariantViolation} before any row is written.
     */
    private void validateBalanced(PostingRequest request) {
        Map<Currency, Long> netByCurrency = new LinkedHashMap<>();
        for (PostingLine line : request.lines()) {
            Money amount = line.amount();
            long signed = line.direction() == Direction.DEBIT ? amount.amountMinor() : -amount.amountMinor();
            netByCurrency.merge(amount.currency(), signed, Math::addExact);
        }
        for (Map.Entry<Currency, Long> net : netByCurrency.entrySet()) {
            if (net.getValue() != 0L) {
                throw new BalanceInvariantViolation(
                        "Unbalanced posting for %s: SUM(debits) - SUM(credits) = %d minor units"
                                .formatted(net.getKey().getCurrencyCode(), net.getValue()));
            }
        }
    }

    private AccountEntity requireAccount(AccountId account) {
        return accounts.findById(account.value()).orElseThrow(() -> new AccountNotFound(account));
    }

    private void requireMatchingCurrency(PostingLine line, AccountEntity account) {
        Currency lineCurrency = line.amount().currency();
        Currency accountCurrency = Currency.getInstance(account.getCurrency());
        if (!lineCurrency.equals(accountCurrency)) {
            throw new AccountCurrencyMismatch(line.account(), lineCurrency, accountCurrency);
        }
    }
}
