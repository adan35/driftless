/**
 * Auth/capture/reversal saga (Spec 03) — the engine that keeps money correct under failure.
 *
 * <p>Layout: {@code api} is the stable surface (lifecycle enums, the {@link
 * io.driftless.auth.api.AuthorizationView} read model, typed domain failures); {@code internal} holds
 * the orchestration (rule eval, hold placement, the bounded partner call, the compensating reversal
 * and crash recovery), its persistence/event/partner/config sub-packages, and the ledger {@code
 * HoldView} implementation; {@code web} is the thin REST controller + error mapping. The saga reuses
 * the published ledger, idempotency/outbox, rules and tokens contracts and reaches the
 * partner-simulator over HTTP only.
 */
package io.driftless.auth;
