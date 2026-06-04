package io.driftless.partnersim.control;

import io.driftless.partnersim.fault.FaultProfile;
import io.driftless.partnersim.fault.FaultRegistry;
import io.driftless.partnersim.fault.PartnerRoute;
import io.driftless.partnersim.partner.state.PartnerStateStore;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Control-plane logic: applies and clears per-route fault profiles and reports the active set. Thin
 * orchestration over {@link FaultRegistry}; holds no behaviour of its own.
 */
@Service
@RequiredArgsConstructor
public class ControlService {

    private final FaultRegistry faultRegistry;
    private final PartnerStateStore stateStore;

    /** Apply (replace) a single route's fault profile, taking effect on the next request. */
    public void apply(FaultProfile profile) {
        faultRegistry.apply(profile);
    }

    /** Reset every route to healthy. */
    public void reset() {
        faultRegistry.resetAll();
    }

    /**
     * Reset every route to healthy and clear recorded request state. Useful for test isolation so a
     * fresh scenario starts from a clean slate.
     */
    public void resetAll() {
        faultRegistry.resetAll();
        stateStore.clear();
    }

    /** The currently active profile for each route. */
    public Map<PartnerRoute, FaultProfile> activeProfiles() {
        Map<PartnerRoute, FaultProfile> profiles = new EnumMap<>(PartnerRoute.class);
        for (PartnerRoute route : PartnerRoute.values()) {
            profiles.put(route, faultRegistry.profileFor(route));
        }
        return profiles;
    }
}
