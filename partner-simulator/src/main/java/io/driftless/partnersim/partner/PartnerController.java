package io.driftless.partnersim.partner;

import io.driftless.partnersim.partner.dto.AuthorizeRequest;
import io.driftless.partnersim.partner.dto.AuthorizeResponse;
import io.driftless.partnersim.partner.dto.CaptureRequest;
import io.driftless.partnersim.partner.dto.CaptureResponse;
import io.driftless.partnersim.partner.dto.ReverseRequest;
import io.driftless.partnersim.partner.dto.ReverseResponse;
import io.driftless.partnersim.partner.state.RequestStateView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The external partner legs the auth saga (Spec 03) calls over HTTP. Thin: validates and delegates to
 * {@link PartnerService}; all behaviour (including injected faults) lives in the service.
 */
@RestController
@RequestMapping("/partner")
@RequiredArgsConstructor
public class PartnerController {

    private final PartnerService partnerService;

    @PostMapping("/authorize")
    public AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request) {
        return partnerService.authorize(request);
    }

    @PostMapping("/capture")
    public CaptureResponse capture(@Valid @RequestBody CaptureRequest request) {
        return partnerService.capture(request);
    }

    @PostMapping("/reverse")
    public ReverseResponse reverse(@Valid @RequestBody ReverseRequest request) {
        return partnerService.reverse(request);
    }

    /** Inspect what the simulator actually did for a request id (404 when nothing is known). */
    @GetMapping("/state/{requestId}")
    public ResponseEntity<RequestStateView> state(@PathVariable String requestId) {
        RequestStateView view = partnerService.state(requestId);
        return view.known()
                ? ResponseEntity.ok(view)
                : ResponseEntity.status(404).body(view);
    }
}
