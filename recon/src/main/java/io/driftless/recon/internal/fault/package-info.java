/**
 * The fault-injection harness: drives the partner-simulator's control plane over HTTP (latency,
 * timeout, fail-before-response, decline, error-rate, duplicate, late-response) so the system can be
 * stressed under downstream failure and proven to stay drift-free. HTTP only — no compile dependency
 * on the separate partner-simulator service.
 */
package io.driftless.recon.internal.fault;
