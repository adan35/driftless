/**
 * The thin REST surface for the Spec 03 saga: {@link
 * io.driftless.auth.web.AuthorizationController} (routing + (de)serialization, every mutating endpoint
 * reading the {@code Idempotency-Key}) and the centralized {@link
 * io.driftless.auth.web.AuthExceptionHandler} mapping typed domain failures to HTTP status codes.
 */
package io.driftless.auth.web;
