package io.driftless.recon;

import net.jqwik.api.Disabled;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * The non-negotiable zero-drift gate: for any random sequence of {@code auth / capture / reversal /
 * fault}, the sum of all journal entries must be exactly zero.
 *
 * <p>Spec 00 only wires the harness — the jqwik engine is on the classpath and this stub is
 * discovered but disabled (jqwik's own {@link Disabled} so the property engine actually skips it).
 * Spec 07 implements the real property against the ledger and removes the annotation. It must never
 * be weakened or deleted.
 */
class LedgerInvariantPropertyTest {

    @Property
    @Disabled("Spec 07 implements the zero-drift property; Spec 00 only wires the harness")
    boolean sumOfAllJournalEntriesIsZero(@ForAll @IntRange(min = 0, max = 100) int operationCount) {
        // Placeholder. Spec 07 replaces this body with: build a random valid sequence of
        // auth/capture/reversal/fault operations against the Ledger, then assert the global
        // SUM(journal entries) == 0 per currency. Until then the harness is wired but inert.
        return operationCount >= 0;
    }
}
