/**
 * Auth SPI (Spec 03): read-only surfaces the auth module publishes for other modules to integrate
 * against without reaching into its internal persistence. {@link
 * io.driftless.auth.spi.HoldReconView} gives the Spec 07 reconciliation job an independent view of
 * hold-vs-authorization consistency (dangling holds, hold cross-foot) so its hold check is genuinely
 * falsifiable.
 */
package io.driftless.auth.spi;
