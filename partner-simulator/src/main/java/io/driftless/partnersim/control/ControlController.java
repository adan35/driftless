package io.driftless.partnersim.control;

import io.driftless.partnersim.fault.FaultProfile;
import io.driftless.partnersim.fault.PartnerRoute;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime control plane for fault injection. Thin: validates and delegates to {@link ControlService}.
 * Lets tests and the demo drive failure modes deterministically without restarting the service.
 */
@RestController
@RequestMapping("/control/faults")
@RequiredArgsConstructor
public class ControlController {

    private final ControlService controlService;

    /** Apply a per-route fault profile; it takes effect on the next matching request. */
    @PostMapping
    public ResponseEntity<FaultProfile> applyFault(@Valid @RequestBody FaultProfile profile) {
        controlService.apply(profile);
        return ResponseEntity.ok(profile);
    }

    /**
     * Restore normal behaviour on every route. When {@code state=true}, also clears recorded request
     * state for full test isolation; by default only faults are cleared.
     */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@RequestParam(defaultValue = "false") boolean state) {
        if (state) {
            controlService.resetAll();
        } else {
            controlService.reset();
        }
        return ResponseEntity.noContent().build();
    }

    /** The currently active fault profile per route. */
    @GetMapping
    public Map<PartnerRoute, FaultProfile> active() {
        return controlService.activeProfiles();
    }
}
