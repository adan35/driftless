# Code Review — Spec 06: Tokenization Lifecycle

**Verdict: GATE: FAIL**

One Critical concurrency defect breaks a spec-mandated state-machine guarantee ("DEACTIVATED is
terminal; no exit") and lets the audit/outbox emit contradictory propagation. It is fixable with a
small, well-understood change (row lock or optimistic version). Two Warnings concern a public-API
deviation from the spec's acceptance criteria and an idempotency-key namespace collision. Resolve the
Critical (and ideally W1/W2) and this is a strong, well-documented implementation.

Reviewer scope: `tokens/src/main/**`, `tokens/src/main/resources/db/migration_tokens/V1__tokens.sql`,
`tokens/pom.xml`, `tokens/src/test/**`. Verified against the three invariants, the frozen module
boundaries, and the Spec 06 acceptance criteria. No source modified.

What is solidly correct (credit where due):
- Outbox atomicity is right: `OutboxWriter.append` is `Propagation.MANDATORY` and is called from
  inside the guard's single transaction together with the status write + history row
  (`TokenLifecycleService.recordTransition` 195–205). Event cannot commit without the write or vice
  versa.
- Immutability: `token_status_history` is append-only with a DB trigger mirroring the ledger
  (`V1__tokens.sql` 53–64); no UPDATE/DELETE path in code; entity columns are `updatable=false`.
- Replay safety on the happy path: illegal transition thrown *inside* the supplier rolls back the
  guard claim, so nothing is written (verified by `IdempotencyAttemptRunner.attempt` @Transactional +
  `nothingExitsTheTerminalDeactivatedState`).
- PAN safety: only `card_ref` stored/logged; test asserts no `pan` column; `Token`/`CreateTokenCommand`
  reject blank refs.
- Conventions: thin controller, DTOs (no JPA leak), constructor injection, SLF4J `{}`, injected
  `Clock`, centralized `@RestControllerAdvice`, inward-only deps (common + ledger + idempotency).

---

## Critical

### C1 — Concurrent conflicting transitions can resurrect a terminal token and double-write history/events (state-machine invariant breach)
**File:** `tokens/src/main/java/io/driftless/tokens/internal/TokenLifecycleService.java:163–192`
(supporting: `TokenEntity.java` has no `@Version`; `TokenRepository.java:7` uses plain `findById`, no
pessimistic lock).

The per-transition idempotency key is keyed by *transition name*
(`tokens:<id>:<TRANSITION>:<seq>`, line 173; web path uses the caller key). Two **different**
transitions on the **same token** therefore get **different keys**, so the guard does **not**
serialize them. Inside the supplier the entity is read with a plain `findById` (line 176) under READ
COMMITTED with no row lock and no optimistic `@Version`, so two concurrent transactions both read the
same stale `from` state, both pass `isLegalFrom`, and the second `UPDATE` simply overwrites the first
(last-writer-wins).

Concrete break — token in `SUSPENDED`, concurrent keyless `deactivate` and `resume` (both legal from
SUSPENDED):
1. `deactivate` reads SUSPENDED → writes DEACTIVATED, history row, `TokenStatusChanged{to=DEACTIVATED}`.
2. `resume` (read SUSPENDED before #1 committed) → its `UPDATE` blocks, then overwrites to ACTIVE,
   writes history row and `TokenStatusChanged{to=ACTIVE}`.

Result: the live token is **ACTIVE after being DEACTIVATED** — the spec's "terminal; no exit"
guarantee (lines 54, 104–105) is violated; the audit trail contains contradictory consecutive rows;
and two conflicting propagation events reach downstream consumers (the auth path could authorize on a
token that was deactivated). The same race also allows duplicate ACTIVATE/SUSPEND application. This is
a money-relevant correctness breach, hence Critical even though it requires concurrency. There is no
test covering concurrent conflicting transitions (`grep` found none), and the pom comment at
`tokens/pom.xml` already acknowledges "the inside-transaction re-validation that guards against a
concurrent state change" — but that re-validation is ineffective without a lock.

**Fix (either):**
- Pessimistic lock the row inside the supplier: add
  `@Lock(LockModeType.PESSIMISTIC_WRITE) Optional<TokenEntity> findById(UUID id)` (a
  `findByIdForUpdate`) on `TokenRepository` and use it at `TokenLifecycleService:176` so the second
  transaction blocks, then re-reads the committed state (DEACTIVATED) and correctly throws
  `IllegalTokenTransition`; **or**
- Add `@Version long version` to `TokenEntity` so the losing transaction fails with
  `OptimisticLockException` and is retried/rejected.
Then add an integration test that fires two conflicting transitions concurrently and asserts exactly
one wins, the loser is rejected, and DEACTIVATED is never escaped.

---

## Warning

### W1 — Keyless `TokenService` masks genuinely-illegal transitions as no-ops, contradicting a spec acceptance criterion
**File:** `tokens/src/main/java/io/driftless/tokens/internal/TokenLifecycleService.java:168–171`

The keyless no-op rule is `from == transition.target()`. Because `ACTIVATE` and `RESUME` share target
`ACTIVE`, and `SUSPEND`/`DEACTIVATE` target their own states, this absorbs transitions that the spec
says must be **rejected**:
- `resume` on an already-`ACTIVE` token → returns no-op ACTIVE (spec lists this exact case as
  illegal: `06-tokenization-lifecycle.md:104` and `:55`).
- `activate` on an already-`ACTIVE` token → no-op instead of `IllegalTokenTransition`.
- `suspend` on an already-`SUSPENDED` token → no-op instead of `IllegalTokenTransition`.

Only the `DEACTIVATE`-when-already-`DEACTIVATED` no-op is semantically safe (terminal). The web/keyed
surface does enforce strict rejection (`TokenControllerIT.aFreshIllegalTransitionIs422`), but the
**public, frozen `TokenService` is the keyless one** (CLAUDE.md module map), and Spec 06's acceptance
criterion "Every illegal transition (e.g. `resume` from `ACTIVE` …) is rejected with
`IllegalTokenTransition`" applies to it. No invariant is breached (nothing is written), but the
documented public contract diverges from the spec.

**Fix:** Distinguish "idempotent replay of the *same* transition" from "already in target via a
different edge." Make the no-op conditional on the *previous* transition actually being this one
(e.g., only treat it as a replay when `transition.isLegalFrom(from)` is false **and** the last history
row's `transition == transition`), or restrict the keyless no-op to `DEACTIVATE` (the genuinely
terminal/idempotent case) and let all others fall through to strict `isLegalFrom` enforcement. If the
permissive behavior is intentional for internal callers, get it blessed by the spec author and update
the Spec 06 acceptance criteria; do not let the public contract silently differ.

### W2 — Derived keyless keys and caller-supplied `Idempotency-Key`s share one guard scope (cross-surface collision / false replay)
**File:** `tokens/src/main/java/io/driftless/tokens/internal/TokenLifecycleService.java:140–142, 173`
(with `IdempotencyGuardService.java:34` `DEFAULT_SCOPE = "default"`).

Both surfaces call the same guard under the single `DEFAULT_SCOPE`. Keyless keys are structured
(`tokens:<id>:<TRANSITION>:<seq>`, `tokens:create:<id>`); a web caller controls the raw
`Idempotency-Key` header. A caller who supplies a key matching the derived scheme for the same
`id`/transition lands on the **same `(scope,key)`**, and since `requestHash` uses the identical
formula on both paths (`hash(transition.name()+"|"+id)`, line 174), it would **replay** an internal
keyless operation's stored result instead of conflicting — e.g., pre-claiming
`tokens:<id>:SUSPEND:<n>` so a later internal protective `suspend` silently replays rather than
applies. The blast radius is limited (the first executor performs the real transition), but sharing an
idempotency namespace between an internal-derived scheme and untrusted client input is unsafe hygiene
for a payments core.

**Fix:** Partition the namespaces. Either pass a distinct scope per surface (introduce a `scope`
parameter on the guard, e.g. `tokens.keyless` vs `tokens.web`), or prefix caller-supplied keys with a
reserved, non-spoofable segment (e.g. `"web:" + idempotencyKey`) and keep derived keys under a
different prefix, so a client can never construct a key that aliases a derived one.

---

## Info

### I1 — Unused `countByTokenId` query on the keyed/web path (minor hot-path waste)
**File:** `tokens/src/main/java/io/driftless/tokens/internal/TokenLifecycleService.java:172`
`long sequenceNo = history.countByTokenId(...)` runs on **every** transition, but `sequenceNo` is only
used by the keyless `orElseGet` (line 173); on the web/keyed path the count is discarded. That is one
extra `COUNT(*)` per web transition. **Fix:** move the count into the `orElseGet` supplier so it is
computed only for the keyless branch: `callerKey.orElseGet(() -> "tokens:" + id + ":" + transition +
":" + history.countByTokenId(id.value()))`.

### I2 — `apply` reads the token twice (pre-guard + in-supplier)
**File:** `TokenLifecycleService.java:164` then `:176`. The pre-guard read serves `TokenNotFound` and
the keyless no-op; the in-supplier read is authoritative. Acceptable, but if W1 is addressed by
restricting the no-op, consider whether the pre-read can be folded into the supplier to avoid the
extra round trip on the keyed path.

### I3 — `@RestControllerAdvice` has no mapping for unexpected `IllegalArgumentException`
**File:** `tokens/src/main/java/io/driftless/tokens/web/TokenExceptionHandler.java`. The service's
`requireKey`/command validation throw `IllegalArgumentException`; today the controller pre-guards the
header and `@Valid` covers the body, so these are unreachable from the web path — but a future caller
path would surface a raw 500. Consider a defensive `IllegalArgumentException → 400` mapping.

### I4 — Production Flyway wiring for `db/migration_tokens` lives only in test scaffolding
**File:** `tokens/src/test/java/io/driftless/tokens/PostgresTestContainer.java:40–49`. The dedicated
location + `flyway_schema_history_tokens` history table is applied by the test `FlywayMigrationStrategy`.
Ensure the `app` monolith wires the same additional location/history (Spec 09) so the table is created
in production; out of scope for this module but flag for the integrator.

---

## Invariant checklist
- **Balance:** N/A (tokens move no money). ✔
- **Immutability (audit):** Append-only `token_status_history` + DB trigger, `updatable=false`
  columns, no UPDATE/DELETE path. ✔
- **Idempotency / outbox atomicity:** Event + history + status write share one transaction via
  `Propagation.MANDATORY` inside the guard supplier; replay runs the supplier zero times. ✔ for the
  single-threaded path; **✘ under concurrency (C1)** for conflicting transitions, and **W1/W2** weaken
  the public contract.

**Regression:** No change to the frozen `ledger.api`/`spi` or other modules' logic. ✔
