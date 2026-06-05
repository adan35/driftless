# Review 03 — Auth Lifecycle Saga

## GATE: FAIL

Three Critical findings. The saga gets the hard parts right — phase-1 hold/AUTHORIZING is
committed before the network call, finalizer/compensation are separate proxy beans (no
self-invocation), every transition loads `FOR UPDATE` + re-checks status under an `@Version`
column, holds are released by status flip (never deleted), reversal of a capture is a new
compensating post, `Idempotency-Key` is enforced (400/409/replay), the RestClient has bounded
connect+read timeouts, no PAN is logged, `Clock` is injected, money is minor units. **But** three
money-correctness / dangling-state defects survive precisely because the zero-drift gate (global
signed journal sum) is *blind* to them: each posts balanced legs or moves no local money, so
`SUM==0` stays true while real value is misplaced or a partner-side authorization dangles forever.

---

## CRITICAL

### C1 — Reversing a *partial* capture posts the inverse of the **authorized** amount, not the **captured** amount
`auth/src/main/java/io/driftless/auth/internal/AuthorizationSaga.java:297`
(`reverseInTx`, `CAPTURED` branch)

```java
case CAPTURED -> {
    bestEffortPartnerReverse(authId, auth.getPartnerRef());
    Money amount = Money.of(auth.getAmountMinor(), currency);   // <-- AUTHORIZED amount
    settlement.postCaptureReversal(authId, AccountId.of(auth.getAccountId()), amount, now);
```

Partial capture is fully supported: `captureInTx` accepts `amountOverride` and
`validateCaptureAmount` (`:260-267`) allows any amount in `(0, authorized]`. The capture posts the
*partial* amount (`:251` uses `amount`), but the entity stores **only** the authorized amount
(`AuthorizationEntity.amountMinor`; there is no `captured_amount` column). So a reverse of a
partially-captured auth posts the inverse of the **authorized** figure.

Example: authorize 30_000, capture 10_000, reverse → reversal posts a 30_000 inverse against a
10_000 settlement. Both legs of the reversal balance, so **the zero-drift gate stays green**, yet
the cardholder is over-credited by 20_000 and settlement is under-credited by 20_000 — real money
moved incorrectly. The only existing test (`AuthSagaIT.reverseFromCapturedPostsInverse...:78`)
captures the *full* amount, masking this.

**Fix:** persist the actually-captured amount on capture (add `captured_amount_minor` to the
`authorization` row / `AuthorizationEntity`, set it in `captureInTx`) and post the reversal inverse
for *that* amount. Until then, either reject reversal of a partial capture or reject partial
capture. Add a partial-capture-then-reverse test asserting `posted`/`available` return to the
pre-capture values and the settlement account nets to zero for the auth.

### C2 — Compensation drives `REVERSED` even when the partner reverse exhausts all retries → partner-side authorization silently dangles
`auth/src/main/java/io/driftless/auth/internal/CompensationRunner.java:48-49` and `:53-74`;
`RecoverySweep.java:57-67`

```java
partnerRef.ifPresent(ref -> reversePartner(authId, ref)); // swallows exhaustion, returns void
finalizer.finalizeReversed(authId, reason, partnerRef);   // ALWAYS runs -> REVERSED (terminal)
```

`reversePartner` tries `compensationMaxAttempts` (default 3) quick attempts and, on exhaustion,
just **logs and returns** (`:69-73`) — it does not signal failure. `compensate` then unconditionally
calls `finalizeReversed`, moving the row to terminal `REVERSED`. The recovery sweep only scans
`AUTHORIZING` / `COMPENSATING` (`:57-67`), so a terminal `REVERSED` row is **never re-driven**. A
partner that is down for more than 3 fast attempts therefore leaves a live partner-side
authorization that is *never* reversed and *never* retried.

This directly contradicts (a) the acceptance criterion "The compensating-reversal path is itself
retried to completion (cannot silently fail)", (b) this class's own docstring ("if it cannot
[finalize] the authorization stays COMPENSATING and the recovery sweep re-drives"), and (c) the
misleading log at `:70` ("hold still released, sweep will retry partner") — the sweep will *not*,
because the row is no longer in-flight. The local hold is released (local drift = 0, which is why
the gate can't see it), but the partner leg dangles — the exact BHN-style failure this spec exists
to prevent.

**Fix:** make `reversePartner` return success/failure. In `compensate`, only call
`finalizeReversed` when the partner reverse **succeeded** *or* when there is provably no partner
side effect to cancel (`discoverPartnerRef` empty **and** partner state known-empty). If a
`partnerRef` exists but the reverse has not yet succeeded, leave the row `COMPENSATING` so the sweep
re-drives `compensate()` (and keep stamping `updated_at` so it stays eligible). Decide explicitly
whether the *local* hold should be released immediately (acceptable for local drift) while keeping
the row in-flight for the partner reverse — but do not reach a terminal state that closes
reconciliation.

### C3 — In-line compensation finalizes `REVERSED` before a slow-but-successful partner records its state → late success is not absorbed
`auth/src/main/java/io/driftless/auth/internal/AuthorizationSaga.java:216`
(`runPartnerAuthorizeLeg`, `NO_RESPONSE` branch) → `CompensationRunner.compensate:44-49`

On a read-timeout (e.g. partner-simulator `TIMEOUT` mode sleeps 2s then *approves*), the in-line
path immediately runs `compensation.compensate`, which:
1. flips `AUTHORIZING → COMPENSATING`,
2. `discoverPartnerRef` via `GET /partner/state` — but the partner has **not yet** recorded its
   side effect (it records *after* the sleep, see `PartnerService.execute` / `completeNormally`),
   so this returns empty,
3. `finalizeReversed` → terminal `REVERSED`, hold released.

Moments later the partner finishes and records an **approved** authorization with a `partnerRef`.
That partner-side auth is never reversed, and because the row is terminal `REVERSED` the sweep
never revisits it. Note the in-line policy ("always compensate on no-response") differs from the
sweep policy (`recoverAuthorizing` first asks `GET /state` and will `markAuthorized` on APPROVED):
the in-line path short-circuits the reconciliation the sweep was designed to perform.

Only the `fail-before-response` case is safe/tested (`AuthSagaIT:124`), because the simulator
records the side effect *before* failing, so `discoverPartnerRef` finds the ref. The common
timeout-then-approve case is the hole, and there is no test for it.

**Fix (shared with C2):** on in-line `NO_RESPONSE`, do not drive to terminal while the partner
outcome is indeterminate. Either leave the row `AUTHORIZING`/`COMPENSATING` for the sweep to
reconcile once partner state is durable (sweep already does the right `GET /state` branch), or have
`compensate` re-query `recoverOutcome` and absorb an APPROVED result by sending the reverse before
finalizing. The terminal `REVERSED` state must imply "partner side is provably cancelled or never
existed".

---

## WARNING

### W1 — A pessimistic row lock + DB connection is held across the partner HTTP call in capture and reverse
`AuthorizationSaga.java:240` (`findByIdForUpdate`) + `:249` (`partnerClient.capture`); same shape in
`reverseInTx` `:283` + `:287/:296`.

`captureInTx`/`reverseInTx` run *inside* the idempotency guard's transaction, so the partner HTTP
call happens while holding the `SELECT ... FOR UPDATE` lock and a pooled DB connection. The
`authorize` path deliberately avoids this (phase-1 commits, partner call is phase-2, outside any
tx) — but capture/reverse do not. It is bounded by the read timeout (~0.8s) so it is not a
correctness bug, but under high traffic it pins a connection + row lock per in-flight capture and
invites pool exhaustion / lock pile-ups on a payments hot path.

**Fix:** mirror the authorize two-phase shape for capture (claim/transition in a short tx, call the
partner outside the lock, post settlement + release hold in a second short tx, all idempotent), or
at minimum document the accepted bound and cap the partner pool/timeout so a stalled partner cannot
exhaust the DB pool.

### W2 — Velocity is recorded in phase 1 but never compensated on decline/timeout-reversal
`AuthorizationSaga.java:171` (`velocityStore.record(...)`).

Velocity is recorded when the hold is placed (`AUTHORIZING`). If the auth is later declined by the
partner (`AuthorizationFinalizer.declineByPartner:54-68`) or compensated to `REVERSED`
(`finalizeReversed:97-118`), the velocity contribution is **not** rolled back. A timed-out or
partner-declined attempt thus keeps counting toward the customer's velocity limit and can cause
spurious future declines. (Rule-decline and inactive-token paths correctly never record — good.)

**Fix:** record velocity only after a confirmed `AUTHORIZED`, or decrement/compensate the velocity
entry on decline/reversal. Since `VelocityStore` is in-memory (Spec 05), prefer recording on
`markAuthorized`.

### W3 — Test + gate blind spot: nothing asserts partner-side reconciliation after timeout-then-approve
`AuthSagaIT.java` / `recon` property test.

`assertZeroDrift` and the property gate only check the *global signed journal sum*. C1/C2/C3 all
keep that sum at 0, so the gate cannot catch them. There is no test for the timeout-then-approve
partner path (only `partnerTimeout...:110` which assumes the partner never approved, and
`failBeforeResponse...:124`).

**Fix:** add an integration test in `TIMEOUT` mode where the partner approves *after* the read
timeout, asserting the partner ends up reversed (or the auth ends `AUTHORIZED`) — i.e. no dangling
partner authorization — and an `AuthEdgeCasesIT` covering exhausted-partner-reverse that asserts the
row stays recoverable (not silently terminal) per the C2 fix.

---

## INFO

- **I1** `AuthorizationSaga.authorize:121-124` — if the in-line phase-2 finalize throws after phase 1
  committed, a replay with the same key returns `replayed=true` and **skips** `runPartnerAuthorizeLeg`,
  so the client gets a transient `AUTHORIZING` view until the sweep heals it (after `stuck-after`).
  Correct-by-design but worth documenting that replay can return a non-terminal state.
- **I2** Capture/reverse have no recovery-sweep coverage: a partner capture that succeeded but whose
  local commit failed (and the client never retries) leaves `AUTHORIZED` locally + captured at the
  partner, with no automatic reconciliation. Acceptable for the client-driven idempotent MVP; note it.
- **I3** No explicit check that the authorization currency matches the cardholder account's currency
  at authorize time (`authorizePhase1`); a mismatch only surfaces later at ledger post time. Consider
  validating against `ledger.balanceOf(account).posted().currency()` up front.
- **I4 (positive)** `@Table(name = "\`authorization\`")` (backtick-quoted) is correctly mapped by
  Hibernate to the Postgres `"authorization"` quoting used in `V1__auth_saga.sql`, so the reserved
  word is consistent across DDL + entity. Partial index `ix_authorization_inflight` keeps the sweep
  scan cheap. Good.

---

## Verdict
**GATE: FAIL** — remediate C1 (partial-capture reversal amount), C2 (exhausted-retry silent drop),
and C3 (premature terminal `REVERSED` on timeout-then-approve) before this ships. All three are
invisible to the current zero-drift gate, so they must be fixed *and* covered by the W3 tests.
