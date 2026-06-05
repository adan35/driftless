package io.driftless.recon.api;

import java.util.List;

/**
 * The outcome of a one-shot demo run (Spec 09): the synthetic load the {@link
 * io.driftless.recon.internal.load.LoadGenerator} drove against the live saga, the partner faults
 * injected through the partner-simulator's control plane, and the reconciliation proof taken once the
 * system settled. The headline a reviewer reads is {@code reconciliation.passed()} with {@code
 * reconciliation.totalDriftMinor() == 0} — provable zero drift even though faults fired and
 * compensating reversals rose.
 *
 * <p>This is a plain DTO, never a JPA entity. It composes the existing {@link LoadResult} and {@link
 * ReconciliationResult} records rather than duplicating their fields.
 *
 * @param accountsSeeded funded cardholder accounts opened for the run
 * @param fundingMinor minor units funded into each account
 * @param faultsInjected human-readable descriptions of the partner faults applied for the run
 * @param load throughput + latency + outcome mix the load generator recorded
 * @param reconciliation the balance proof taken after the system settled
 */
public record DemoResult(
        int accountsSeeded,
        long fundingMinor,
        List<String> faultsInjected,
        LoadResult load,
        ReconciliationResult reconciliation) {

    public DemoResult {
        faultsInjected = List.copyOf(faultsInjected);
    }
}
