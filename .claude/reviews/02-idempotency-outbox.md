# Code Review - Spec 02: Idempotency & Outbox

**Reviewer:** principal-engineer (final gate)
**Scope:** idempotency/ (idempotency replay guard + transactional outbox)
**Build:** full reactor green; QA 17 cases / 39 methods, all pass on real Postgres (Testcontainers 16-alpine).
**Verdict: GATE 02 - PASS.** No invariant breach, no drift path, no double-effect, no double-apply trap, no SQL injection. Findings below are Warning/Info hardening for the multi-instance future and observability; none block.

This is the system-wide implementation of invariant 3 and the spine Spec 03/07 build on. Weighted heaviest; every production file plus the key ITs read. It is correct.

## What is SOUND (the load-bearing claims)

- Replay always returns the stored result, no re-execution. IdempotencyAttemptRunner.attempt (IdempotencyAttemptRunner.java:44-67) runs claim-insert -> operation.get() -> complete() in ONE @Transactional. The runner is a separate proxied bean from IdempotencyGuardService (correct - a self-call would bypass the proxy). A committed record is always COMPLETED; replayExisting (line 69-89) returns it with replayed=true and never calls the operation.
- Concurrent-claim race is correct and bounded. The composite-PK unique constraint (V1__idempotency_outbox.sql:29) lets one win at saveAndFlush; the loser gets DataIntegrityViolationException -> ConcurrentClaimException -> rollback -> retry (IdempotencyGuardService.java:53-64). On retry the loser's saveAndFlush BLOCKS on the winner's uncommitted unique-key insert (Postgres), so there is no busy-spin: it unblocks when the winner commits (-> next pre-read replays) or aborts (-> loser becomes winner). Resolves in <=2 attempts; MAX_ATTEMPTS=5 fail-closes with IllegalStateException (500), never an infinite loop or a wrong result.
- Same key + different requestHash is reliably a typed conflict. replayExisting (line 70-78) compares requestHash BEFORE touching status/result and throws IdempotencyConflict (a DomainException, mapped to 409 by Spec 03). Never a silent wrong result. Persistable.isNew() (IdempotencyRecordEntity.java:96-98) forces an INSERT so the unique-constraint backstop fires.
- StoredResult round-trip is sound. Caller owns serialization; replay reconstructs via Class.forName(responseType) + Jackson (IdempotencyAttemptRunner.java:91-102), JSR-310 registered (IdempotencyAutoConfiguration.java:45). Deserialization failure is a loud IllegalStateException, not a fabricated value.
- Outbox atomicity is real. OutboxWriterService.append is @Transactional(propagation = MANDATORY) (OutboxWriterService.java:29) - joins the caller's transaction or fails fast.
- Relay is crash-safe and ordering-preserving (single relay). OutboxBatchPublisher.publishBatch (OutboxBatchPublisher.java:44-59) locks oldest PENDING FOR UPDATE SKIP LOCKED in id ASC (OutboxEventRepository.java:26-34, lock.timeout=-2) and does publish-then-mark in the same transaction. Death before commit re-presents the whole batch; a mid-batch publish failure rolls the entire batch back to PENDING (attempts revert to 0). At-least-once + id-keyed dedup = effectively-once.
- Immutability/data model. Both tables append-only except status (+published_at/attempts); CHECK constraints pin the enums (V1:30,46). No UPDATE/DELETE path exists.
- Security. Every query is JPQL/Spring-Data with bound :params. No string-concatenated SQL, no secrets, no PAN. The relay logs id/aggregate/eventType only.

On QA's batch-failure risk probe: concur. At-least-once with a mandated id-keyed idempotent consumer is the correct call, NOT a latent double-apply trap - provided the contract is enforced on every consumer Spec 03 wires in (W1). The EventPublisher + PublishedOutboxEvent.id() seam is clean for a real broker.

## Findings

### Warning

W1 - Per-aggregate ordering holds only for a SINGLE relay instance; SKIP LOCKED can invert order across concurrent relays.
OutboxEventRepository.lockNextPending (OutboxEventRepository.java:26-34) javadoc claims "per-aggregate ordering is preserved because global id order is a superset of each aggregate's order." True for ONE relay. With two app instances, relay A can lock events {1,2} for aggregate X while relay B (SKIP LOCKED) skips them and claims a newly-appended {3} for the same aggregate X - and B may publish {3} before A publishes {1,2}, inverting per-aggregate order (a stated acceptance criterion).
- Today latent: the relay is @Scheduled(fixedDelay) on Spring's default single-threaded scheduler, so one in-flight sweep per JVM, and the MVP is single-instance. No live defect.
- Fix (before multi-instance): (a) enforce single-relay via a DB advisory lock / ShedLock around the sweep, or (b) claim at aggregate granularity (lock the lowest pending id per aggregate_id and never skip a locked aggregate's later rows). Until then, add a one-line note to the repository javadoc and Spec 02 that the ordering guarantee assumes a single active relay.

### Info

I1 - attempts is not a durable failure counter (observability blind spot). recordAttempt() (OutboxEventEntity.java:74-76) runs inside the batch transaction, so a failed delivery's increment rolls back; rows show attempts==1 after two real attempts (OutboxBatchFailureIT.rolledBackBatchDoesNotDurablyCountFailedAttempts). Correct for crash-safety (status is source of truth) but attempts cannot surface a poison event that re-fails every sweep with no visibility. Matches QA Minor-2. Fix (Spec 08): a committed failure metric (Micrometer counter or a separate committed failed_attempts column written in a REQUIRES_NEW step on the catch path) / dead-letter threshold. Not required for this gate.

I2 - No statement/lock timeout on the claim-insert block. The losing claimant blocks inside saveAndFlush for the winner's operation duration (bounded by the operation; for Spec 03 a bounded-timeout step). When Spec 03 wires the guard around the partner call, confirm the connection lock_timeout/statement_timeout is set so a pathologically slow winner cannot pin a pool connection. Defensive.

I3 - replayExisting "committed IN_PROGRESS -> retry" branch (IdempotencyAttemptRunner.java:79-85) is unreachable in the single-transaction model. Correct to keep as a defensive backstop; acknowledged in code and the 0.70 jacoco branch floor. No action.

## Verdict

GATE 02: PASS. The replay guard and transactional outbox are correct, crash-safe, immutable, with genuine Testcontainers-Postgres proof of every acceptance criterion. Track W1 before any multi-instance run; fold I1 into Spec 08 observability.
