package io.driftless.recon.internal.fault;

import io.driftless.recon.internal.config.ReconProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Drives the partner-simulator's fault-injection control plane over HTTP so the load generator can
 * run against a misbehaving downstream — latency, timeout, fail-before-response, decline, error-rate,
 * duplicate and late-response — and Driftless can demonstrate that injected downstream failures never
 * produce drift.
 *
 * <p>It is HTTP only (no compile dependency on the separate partner-simulator): it POSTs {@link
 * FaultRequest} bodies (whose field names match the simulator's {@code FaultProfile}) to {@code
 * /control/faults} and resets via {@code /control/faults/reset}. Process-level faults (forcing the
 * auth recovery sweep, dropping/recovering the outbox relay) are injected by the reliability tests
 * directly against those components; this harness owns the partner-side network faults.
 */
@Slf4j
@Component
public class FaultInjectionHarness {

    private final RestClient controlClient;

    @Autowired
    public FaultInjectionHarness(ReconProperties properties) {
        this(buildClient(properties));
    }

    /** Test seam: inject a pre-built client (e.g. pointed at an in-test control stub). */
    public FaultInjectionHarness(RestClient controlClient) {
        this.controlClient = controlClient;
    }

    /** Apply a fault profile to a route; it takes effect on the next matching partner request. */
    public void inject(FaultRequest request) {
        controlClient.post().uri("/control/faults").body(request).retrieve().toBodilessEntity();
        log.info(
                "injected fault route={} mode={} applyToNext={}",
                request.route(),
                request.mode(),
                request.applyToNext());
    }

    /** Restore normal behaviour on every route (faults only; recorded request state is preserved). */
    public void reset() {
        controlClient.post().uri("/control/faults/reset").retrieve().toBodilessEntity();
        log.info("reset all partner faults");
    }

    /** Restore normal behaviour and clear the partner's recorded request state (full test isolation). */
    public void resetAll() {
        controlClient.post().uri("/control/faults/reset?state=true").retrieve().toBodilessEntity();
        log.info("reset all partner faults and cleared recorded state");
    }

    private static RestClient buildClient(ReconProperties properties) {
        ReconProperties.Partner partner = properties.getPartner();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) partner.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) partner.getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(partner.getControlBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
