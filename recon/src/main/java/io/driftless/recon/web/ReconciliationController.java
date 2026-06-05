package io.driftless.recon.web;

import io.driftless.recon.api.RcaReport;
import io.driftless.recon.api.ReconciliationResult;
import io.driftless.recon.internal.RcaReporter;
import io.driftless.recon.internal.ReconciliationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST surface for the Spec 07 reconciliation job: routing and serialization only — all logic
 * lives in {@link ReconciliationService} / {@link RcaReporter}. It exposes the on-demand run, the
 * latest result, a result by id, and the RCA report — the reads Spec 08's dashboard consumes.
 *
 * <p>Reconciliation is read-only over the ledger; running it has no idempotency key because it
 * mutates nothing of the business state (it only appends an evidence row) — a re-run simply produces
 * a fresh proof. The api records returned are plain DTOs, never JPA entities.
 */
@Slf4j
@RestController
@RequestMapping("/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final RcaReporter rcaReporter;

    /** Run the balance proof now and return the persisted result. */
    @PostMapping("/run")
    public ReconciliationResult run() {
        return reconciliationService.run();
    }

    /** The most recent persisted result, or 404 if none has run yet. */
    @GetMapping("/latest")
    public ResponseEntity<ReconciliationResult> latest() {
        return reconciliationService.latest().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }

    /** A persisted result by id, or 404. */
    @GetMapping("/{id}")
    public ResponseEntity<ReconciliationResult> find(@PathVariable UUID id) {
        return reconciliationService.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }

    /** An RCA-style report for a persisted result (what drifted, where, candidate cause), or 404. */
    @GetMapping("/{id}/rca")
    public ResponseEntity<RcaReport> rca(@PathVariable UUID id) {
        return reconciliationService
                .find(id)
                .map(rcaReporter::report)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
