package io.driftless.recon.web;

import io.driftless.recon.api.DemoResult;
import io.driftless.recon.internal.demo.DemoRunner;
import io.driftless.recon.web.dto.DemoRunRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin, clearly-labelled <strong>demo / seed-only</strong> endpoint for Spec 09: it drives the live
 * composed system (seed → load → fault injection → settle → reconcile) so a reviewer can watch the
 * zero-drift dashboard hold at $0 under faults from a single call. Routing + serialization only; all
 * logic lives in {@link DemoRunner}.
 *
 * <p>It is <em>not</em> a production money-movement endpoint: it only exercises the ordinary
 * authorize / capture / reverse / fault paths plus a normal balanced funding post, so it cannot
 * corrupt the invariants. It is <strong>disabled by default</strong> and only registered when {@code
 * driftless.demo.enabled=true} (the docker profile sets it; a normal/prod boot leaves it absent so
 * {@code POST /demo/run} returns 404), intended for the local demo stack, never an exposed surface.
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "driftless.demo.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class DemoController {

    private final DemoRunner demoRunner;

    /** Run the demo with the given (all-optional, bounded) parameters and return load + reconciliation proof. */
    @PostMapping("/demo/run")
    public DemoResult run(@Valid @RequestBody(required = false) DemoRunRequest request) {
        DemoRunRequest req = request == null ? DemoRunRequest.empty() : request;
        log.info(
                "demo endpoint invoked count={} concurrency={} accounts={} faultMix={}",
                req.count(),
                req.concurrency(),
                req.accounts(),
                req.faultMix());
        return demoRunner.run(
                req.count(), req.concurrency(), req.accounts(), req.fundingMinor(), req.faultMix(), req.seed());
    }
}
