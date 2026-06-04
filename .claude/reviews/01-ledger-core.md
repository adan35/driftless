# Review 01 — Ledger Core (FINAL GATE)

**Reviewer:** code-reviewer (principal-engineer pass) · **Date:** 2026-06-02
**Verdict:** **GATE: PASS** — Spec 01 is correct by construction; the frozen `io.driftless.ledger.api`/`spi`
contract is byte-shape-preserved (no freeze breach); downstream money modules (02 / 04 / 05 / 06) may build on it.

Reviewed against the live code: the frozen contract, `LedgerService`, `LedgerMapper`, the `persistence/`
entities + repositories, the `error/` types, both Flyway migrations, `common.Money`, and all tests.
QA had already independently re-run `clean verify` green on real Postgres (Testcontainers `postgres:16-alpine`).

## Invariant 1 — Balance (scrutinized hardest): UPHELD
`LedgerService.validateBalanced` (`ledger/.../internal/LedgerService.java:177-191`) computes the net **per
currency** in a `Map<Currency, Long>` using `Math.addExact` (overflow-safe), DEBIT positive / CREDIT negative,
and rejects with `BalanceInvariantViolation` if any currency nets non-zero. Pure `long` minor units — no float.
Validation runs **before** any entity is built (line 99, before the assembly loop 102-120) and the method is
`@Transactional` (line 88), so every rejection path (unbalanced; currency-mismatch 197-203; account-not-found
193-195) throws before `saveAndFlush` → zero partial rows. DB `CHECK (amount_minor > 0)` (`V1:49`) + the
`PostingLine` positive guard are belt-and-suspenders. `balanceOf` is derived purely by
`SUM(CASE WHEN DEBIT ... ELSE -...)` (`JournalEntryRepository.java:27-34`) — no stored balance column exists.
The jqwik property (`LedgerZeroDriftPropertyTest`, tries=30, non-vacuous against real Postgres) asserts
`globalSignedSumMinor == 0` per currency. No code path can produce a nonzero global sum.

## Invariant 2 — Immutability: UPHELD
No UPDATE/DELETE/save-of-existing path against `journal_entry`. Entities are insert-only: `JournalEntryEntity`
has no setters, all columns `updatable = false`; `TransactionEntity` cascades PERSIST only and implements
`Persistable.isNew()` to force INSERT (not merge). The V2 trigger (`V2__journal_entry_append_only.sql:21-24`)
fires `BEFORE UPDATE OR DELETE ... FOR EACH ROW` and `RAISE EXCEPTION` — verified by two runtime tests (direct
UPDATE + direct DELETE both rejected). Corrections are new compensating posts (`correctionIsANewCompensatingPost...`
confirms 4 surviving rows). Trigger-vs-REVOKE rationale (superuser GRANT bypass under Testcontainers) is sound.

## Invariant 3 — Idempotency: UPHELD for Spec 01 scope
`post` (`LedgerService.java:89-140`) is lookup-first by `idempotencyKey` (returns original `PostingResult`,
`replayed=true`, zero new rows), backed by unique index `ux_transaction_idempotency_key` (`V1:33`), with a
`catch (DataIntegrityViolationException)` that re-reads the winner on a concurrent race — correct, no
double-effect, no lost-update window. `saveAndFlush` (not `save`, line 123) deliberately surfaces the
constraint violation inside the try/catch so the race fallback works. Replay test confirms 0 new rows.

## Money / types / time: CLEAN
Minor units (`long`) throughout; `Money` uses `addExact`/`subtractExact`/`negateExact` and rejects
cross-currency ops. No `double`/`float`/`BigDecimal` in money math (only Javadoc hits). Line currency must
equal account currency (`requireMatchingCurrency` 197-203). `postedAt = clock.instant()` (line 106) from the
injected `Clock`; `occurredAt` from the caller. No `Instant.now()` / `new Date()` / `System.out` in prod code.

## Frozen contract: INTACT — no freeze breach
`PostingLine.java` and `PostingRequest.java` record headers are byte-identical; only additive compact-constructor
guards + `java.util.Objects` imports. All other `api`/`spi` files unmodified. No component/type/accessor/signature
change. Guards strengthen invariants (positive amount, ≥2 lines, defensive `List.copyOf`) without altering shape.

## Persistence / app / security: SOUND
Mapper never leaks JPA entities (returns only `api` records). Associations LAZY; `findByIdempotencyKey` and
`findWithEntriesById` use `@EntityGraph(attributePaths="entries")` to avoid N+1; `balanceOf` sums in SQL.
Migrations versioned/append-only with correct FKs, `ix_journal_entry_account`, and the unique idempotency index.
All queries parameterized — no injection. `open-in-view=false`, `ddl-auto=validate` (Flyway owns schema).
The `app` change is genuinely test-only (test class + test-scope pom deps); production `application.properties`
unchanged. Deferring `app`'s production datasource to Spec 09 is reasonable. JaCoCo gate (LINE≥0.85, BRANCH≥0.80)
is correctly scoped to `io.driftless.ledger.internal.*` and is meaningful, not hollow.

## Findings

### Critical
None. No invariant breach, drift path, mutation of settled history, double-effect, or freeze breach.

### Warning
None that block the gate.

### Info (all non-blocking — carried forward)
- **I1 / M1 — silent body-mismatch on reused key (→ Spec 02).** `LedgerService.java:91-96`: a reused key with a
  different body silently returns the original and applies nothing. Correctly NOT a Spec 01 breach (key honored,
  nothing extra written, no drift), and 409-on-different-body is Spec 02 REST machinery. Interim hazard: an
  accidental key reuse is a silent no-op the current `replayed=true` log doesn't flag. Recommended (cheap,
  non-breaking): in the replay branch, fingerprint incoming lines+description vs the stored tx and on mismatch
  `log.warn("post replay key={} body DIFFERS from original txId={}; ignoring new body (Spec 02 will 409)", ...)`.
  **PM decision:** the body-fingerprint + 409 belongs to Spec 02's idempotency generalization; carried there as an
  explicit acceptance criterion rather than hand-patching the gated ledger core now.
- **I2 / M2 — concurrent duplicate-key race (→ Spec 02).** Fallback is correct and the unique index is the true
  arbiter; no two-thread test forces it. Add an explicit concurrent test when Spec 02 generalizes idempotency.
- **I3 — `entriesFor` returns `List`, not `Slice`/`Page` (→ Spec 07).** Functionally correct (LIMIT/OFFSET, paging
  test passes); the frozen signature returns `List<JournalEntry>`. Deep OFFSET paging on very large accounts is a
  future recon concern (keyset pagination on `sequence_no`).
- **I4 — unused repo seams.** `currenciesFor`/`allCurrencies`/`globalSignedSumMinor` are reconciliation/property
  seeds (the property test uses `globalSignedSumMinor`). Acceptable; no action.
- **I5 — read-path logging at DEBUG (QA M3).** `balanceOf`/`entriesFor` at DEBUG; post-path at INFO is sufficient.
  Fine for Spec 01; Spec 07 may add a structured recon hook.

## Acceptance-criteria traceability: all satisfied
Unbalanced→typed reject + nothing written; currency-mismatch reject; balanced 2-line→2 entries + 1 tx; replay→
original TxId + `replayed=true` + 0 rows; `balanceOf`==summation and `available==posted` (no holds);
`available==posted−activeHoldTotal` (HoldView); no UPDATE/DELETE code path + DB rejects both at runtime; global
SUM==0 via random jqwik sequence; integration on real Postgres via Testcontainers. Each proven by a green test.

**GATE: PASS.** The three Info carry-forwards (I1 WARN-log/409 → Spec 02; I2 concurrent test → Spec 02;
I3 keyset paging → Spec 07) are non-blocking.
