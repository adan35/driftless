/**
 * The saga's HTTP integration with the external partner-simulator (Spec 04): the bounded-timeout
 * {@link io.driftless.auth.internal.partner.PartnerClient}, the normalized {@link
 * io.driftless.auth.internal.partner.PartnerAuthOutcome}, the typed {@link
 * io.driftless.auth.internal.partner.PartnerUnavailableException}, and the wire DTOs ({@link
 * io.driftless.auth.internal.partner.PartnerMessages}). Integration is HTTP only — no compile
 * dependency on the partner service.
 */
package io.driftless.auth.internal.partner;
