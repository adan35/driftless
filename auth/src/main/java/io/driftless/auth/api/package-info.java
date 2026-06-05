/**
 * Public, stable surface of the Spec 03 auth saga: the lifecycle {@link
 * io.driftless.auth.api.AuthorizationStatus} and {@link io.driftless.auth.api.HoldStatus} enums, the
 * immutable {@link io.driftless.auth.api.AuthorizationView} read model, and the typed domain failures
 * ({@link io.driftless.auth.api.AuthorizationNotFound}, {@link
 * io.driftless.auth.api.IllegalAuthorizationState}) the web layer maps to HTTP. Carries no I/O or
 * business logic — the orchestration lives in {@code io.driftless.auth.internal}.
 */
package io.driftless.auth.api;
