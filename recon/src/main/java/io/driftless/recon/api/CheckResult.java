package io.driftless.recon.api;

/**
 * The outcome of a single {@link ReconCheck}: whether it held, the total absolute drift it
 * measured, and a short log-safe explanation.
 *
 * @param check which check this is
 * @param passed {@code true} when the check held
 * @param driftMinor total absolute drift the check measured, in minor units ({@code 0} on pass)
 * @param detail a short human-readable summary (e.g. "USD net 0" or "2 unbalanced transactions")
 */
public record CheckResult(ReconCheck check, boolean passed, long driftMinor, String detail) {

    /** A clean pass with no drift. */
    public static CheckResult pass(ReconCheck check, String detail) {
        return new CheckResult(check, true, 0L, detail);
    }

    /** A failure carrying the measured drift and an explanation. */
    public static CheckResult fail(ReconCheck check, long driftMinor, String detail) {
        return new CheckResult(check, false, driftMinor, detail);
    }
}
