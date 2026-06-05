/**
 * The Spec 03 saga internals: the orchestrating {@link io.driftless.auth.internal.AuthorizationSaga}
 * (authorize/capture/reverse under the idempotency guard), the transactional partner-leg writers
 * ({@link io.driftless.auth.internal.AuthorizationFinalizer}), the bounded-timeout {@link
 * io.driftless.auth.internal.CompensationRunner}, the crash {@link
 * io.driftless.auth.internal.RecoverySweep}, the hold lifecycle ({@link
 * io.driftless.auth.internal.Holds}) and {@link io.driftless.auth.internal.HoldViewImpl} SPI
 * implementation, the {@link io.driftless.auth.internal.SettlementPoster}, and the {@link
 * io.driftless.auth.internal.LifecycleOutbox} event seam. Reuses the published ledger / idempotency /
 * outbox / rules / tokens contracts — never their internals.
 */
package io.driftless.auth.internal;
