# Review 07 — Reconciliation & Reliability (the zero-drift gate)

**Reviewer:** Principal engineer, zero-drift gate owner · **Scope:** `recon/**` (read-only review)

## GATE: FAIL

The **property test itself (`LedgerInvariantPropertyTest`) is genuinely strong and falsifiable** — it
drives the real saga with real faults, settles with a hard `assertNoInFlight` guard the time-budget
cannot mask, and checks all three invariants against real committed state. That part I would ship.

But the *continuous reconciliation job* — the other half of this deliverable and the thing the README
will point at as "continuous proof" — ships **two of its four checks as tautologies that can never
fail**. One of them, `HOLD_CONSISTENCY`, is the check that is supposed to prove
`available == posted − activeHoldTotal`; as written it compares a value to itself and is structurally
incapable of detecting a phantom/dangling hold. A proof with a hollow pillar is not a proof, so the
gate fails until the hold check is given an independent source of truth.

Money is not at acute risk (the balance invariant is genuinely proven by `GLOBAL_ZERO` *and* by the
property test, and the property test's `assertCleanHolds` genuinely catches dangling holds), but the
reconciliation **job** must not advertise a vacuous detector as a real one.

---

## Critical

### C1 — `HOLD_CONSISTENCY` is a tautology: it compares a value to itself and can never fail
`recon/.../internal/ReconciliationService.java:224-253`

The check sources **both sides** of its comparison from the **same repository queries that
`balanceOf` already uses**, so the deltas are identically zero by construction:

- `balanceOf` (LedgerService.java:147-149) computes
  `posted = JournalEntryRepository.netPostedMinor(...)` and
  `available = posted − HoldViewImpl.activeHoldTotal(...)`, where `HoldViewImpl` (HoldViewImpl.java:30)
  delegates to `HoldRepository.activeHoldTotalMinor(...)`.
- The check (lines 230-235) recomputes `postedIndependent = journalEntries.netPostedMinor(...)` (the
  *same* method/bean) and `activeHoldTotal = holds.activeHoldTotalMinor(...)` (the *same* method/bean),
  then asserts `balance.posted() == postedIndependent` and
  `balance.available() == postedIndependent − activeHoldTotal`.

Therefore `postedDelta ≡ 0` and `availableDelta ≡ 0` for every account, always. A genuinely broken
state — e.g. a hold left `ACTIVE` after capture/reversal (a phantom hold) — is subtracted identically
on both sides, so `availableDelta` stays 0 and the check **passes while `available` is actually
wrong**. This is exactly the "non-detecting detector" the spec warns against.

**Fix:** give the check an *independent* notion of what the active holds should be, rather than
re-deriving available from the same hold rows. Concretely, detect holds that are `ACTIVE` but whose
authorization is in a terminal state (`CAPTURED`/`REVERSED`/`DECLINED`) — a dangling hold — and/or
cross-check `SUM(active hold amounts)` against `SUM(amountMinor)` of authorizations still in
`AUTHORIZED`. Either gives a real, falsifiable signal. Add an IT that injects a dangling `ACTIVE` hold
via direct SQL (mirroring `DriftDetectionIT`) and asserts the check fails and names the offender —
without such a test, the tautology would have been impossible to spot from green CI.

---

## Warning

### W1 — `PER_ACCOUNT` is also non-falsifiable given the journal_entry → account foreign key
`recon/.../internal/ReconciliationService.java:198-218`, `LedgerReconRepository.java:54-62`

`netForKnownAccounts(currency)` sums entries `WHERE accountId IN (SELECT a.id FROM AccountEntity)` and
compares it to the unrestricted `globalSignedSumMinor(currency)`. But `journal_entry` has
`CONSTRAINT fk_journal_entry_account FOREIGN KEY (account_id) REFERENCES account(id)`
(`ledger/.../V1__ledger_core.sql:46`), so **every** entry references a known account. The restricted
and unrestricted sums are always equal; `diff ≡ 0`; the check can never fail. It is sold in the
`ReconCheck.PER_ACCOUNT` javadoc as catching "drift no single-transaction check would catch," which is
misleading.

**Fix:** either (a) implement the spec's actual intent — reconcile each account's summed balance
against a cached/snapshot balance if/when one exists — or (b) replace it with a check that *can* fail
at the DB you actually have (e.g. per-transaction debit-vs-credit currency mismatch, or entries whose
account currency disagrees with the entry currency). If neither is meaningful yet, drop the check
rather than present a tautology as a pillar of the proof.

### W2 — Recon main source reaches into other modules' `internal` packages (boundary breach)
`ReconciliationService.java:6,10-11`; `HoldReconRepository.java:3`; `LedgerReconRepository.java:3`;
`OutboxReconRepository.java:3`

CLAUDE.md: "features depend on `ledger`/`common`, **never on each other's internals** — integrate
through published `api`/`spi` packages and outbox events." Recon's *production* code imports and binds
to `io.driftless.ledger.internal.persistence.JournalEntryEntity/JournalEntryRepository`,
`io.driftless.auth.internal.persistence.HoldEntity/HoldRepository`, and
`io.driftless.outbox.internal.persistence.OutboxEventEntity/OutboxStatus`. This couples recon to three
sibling modules' private JPA schemas; a rename/refactor inside any of them silently breaks recon, and
the "frozen `ledger.api`" boundary is bypassed (the ledger's published `Ledger` SPI deliberately has no
global-sum method, and recon worked around that by reaching into the internal repo).

The pom/`.docs` justify recon as "a consumer of committed state," which is a legitimate design stance,
but it should be made *explicit and supported* rather than reaching past the stated boundary:

**Fix:** publish the read surface recon needs as `spi`/`api` on the owning modules (e.g.
`ledger.spi.LedgerReconView` exposing `globalSignedSumMinor`/`findUnbalancedTransactions`,
`auth.spi` for active-hold accounts, `idempotency.spi` for stuck-outbox counts) and have recon depend
on those. At minimum, flag this as a deliberate, ADR-recorded exception to the boundary rule so the
next reviewer doesn't treat it as an accident. (Tests reaching into `internal` is acceptable; the
concern is the `src/main` coupling.)

---

## Info

### I1 — Redundant journal scan in `PER_ACCOUNT`
`ReconciliationService.java:201` recomputes `journalEntries.globalSignedSumMinor(currency)` already
computed in `checkGlobalZero` (line 149). For a batch job this is harmless, but pass the value through
to avoid a second full-table SUM per currency.

### I2 — `HOLD_CONSISTENCY` does 3 queries per account-with-holds (N+1)
`ReconciliationService.java:227-231` runs `balanceOf` + `netPostedMinor` + `activeHoldTotalMinor` per
account in a loop. Bounded by the number of accounts carrying active holds and acceptable for a
scheduled batch, but once C1 is reworked, prefer a single set-based query that returns the offending
accounts directly rather than looping.

### I3 — JaCoCo package gate doesn't cover `load`/`fault` subpackages
`recon/pom.xml` `jacoco-check` includes only `io.driftless.recon.internal` and `io.driftless.recon.web`
(non-recursive PACKAGE includes). `io.driftless.recon.internal.load` and `...internal.fault` are not
gated. They are exercised by ITs, but the floor doesn't bite on them — widen the includes
(`io.driftless.recon.internal.*`) so the gate matches the claim.

### I4 — "Fixed seed" reproducibility claim may be non-functional
`junit-platform.properties:7` sets `jqwik.seeds.whenfailing.default = 424242`, and the test javadoc
claims "a fixed default seed." That is not a standard jqwik configuration key (jqwik seeds are fixed
per-`@Property(seed=...)`; the global knob is `jqwik.afterfailure.default`). Reproducibility is in
practice provided by jqwik reporting the failing `@ForAll long seed` and replaying it — which is fine
— but the property line likely does nothing. Either set the seed via `@Property(seed = "424242")` on
the gate method or correct the comment so a future maintainer doesn't rely on a no-op.

### I5 — `POST /reconciliation/run` carries no `Idempotency-Key`
`ReconciliationController.java:36-39`. CLAUDE.md requires `Idempotency-Key` on mutating endpoints. The
controller javadoc argues the run mutates no *business* state (it only appends an evidence row), which
is defensible, but document/confirm this is an accepted exception in the `@RestControllerAdvice`/spec
so it isn't read as a missed control. No security input-validation issues otherwise; the `{id}` path is
a typed `UUID`.

### I6 — Shared-DB exact-equality assertions are fragile across ITs
`DriftDetectionIT.java:46,56,79` assert `driftMinor == 12_345` / `globalSignedSum() == 12_345` /
`== 9_900`, which assume the JVM-singleton Postgres is globally balanced (sum 0) at the moment of
injection. `AbstractReconIT.rebalanceInjectedDrift` (lines 132-151) correctly hands back a balanced
ledger via an append-only compensating CREDIT (good — never UPDATE/DELETE), and every other IT either
rebalances or self-heals to zero, so it holds **today**. But any future test that introduces drift
*without* going through `injectUnbalancedEntry` (so it isn't tracked/rebalanced) would silently make
these exact-equality assertions fail or, worse, mask. Consider asserting the *delta* the test itself
introduced (snapshot `globalSignedSum()` before injection, assert the difference) rather than an
absolute value, to make the ITs robust to shared-DB residue. (Note: the property gate uses its own
dedicated container — good isolation there.)

---

## What is genuinely solid (so it isn't "fixed" away)

- **The property gate is real and falsifiable.** `LedgerInvariantPropertyTest` generates random
  sequences that include captures and reverses (lines 161-171), faults actually fire and reach the
  saga (`TIMEOUT` sleeps 2000ms past a 0.4s read-timeout → `NO_RESPONSE` → compensate;
  `FAIL_BEFORE_RESPONSE` → 500 → compensate), and the settle loop (lines 293-305) is *guarded* by
  `assertNoInFlight` (lines 175-176, 236-243) so a budget exhaustion can NOT pass off an unsettled
  state as green. All three assertions read real committed state: signed journal sum per currency
  (228-234), real replay + row-count for no-double-effect (245-261), and real hold-row/`balanceOf`
  inspection for clean holds (263-289).
- **`GLOBAL_ZERO` is a real detector**, proven by `DriftDetectionIT` injecting a deliberately
  unbalanced row via direct SQL (bypassing balanced `post()`), and naming the offending
  transaction + account.
- **`OUTBOX_CONSISTENCY` is a real detector**, proven by `ReconciliationServiceIT`.
- **No auto-fix.** The service only reads and appends `reconciliation_result`; no UPDATE/DELETE on
  `journal_entry`, no "correcting" post. Test cleanup uses append-only compensating entries.
- Injected `Clock` throughout recon main (no `Instant.now()`/`System.out`), thin controller returning
  DTO records, constructor injection, `@ConfigurationProperties`, SLF4J `{}`, recon-owned Flyway
  location with its own history table, and no change to the frozen `ledger.api`/`spi`.

## Required before this can be called a proof
1. Rework **C1** (`HOLD_CONSISTENCY`) to use an independent source of truth + add a failing-state IT.
2. Make **W1** (`PER_ACCOUNT`) falsifiable or remove it.
3. Decide and document **W2** (boundary) — publish an SPI or record the ADR exception.
