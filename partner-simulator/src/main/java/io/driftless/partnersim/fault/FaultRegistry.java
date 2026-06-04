package io.driftless.partnersim.fault;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Holds the active fault configuration per partner route and resolves a deterministic, seeded {@link
 * FaultDecision} for each incoming request.
 *
 * <p>Determinism contract:
 *
 * <ul>
 *   <li>Applying a profile installs a fresh {@link Random} seeded from {@code profile.seed()}, so the
 *       sequence of probabilistic outcomes over a request stream is fully reproducible for a given
 *       seed — this is what makes CI assertions on {@code error-rate}/{@code decline} stable.
 *   <li>When {@code applyToNext > 0} the fault fires for exactly the next N matching requests then
 *       auto-clears to {@link FaultMode#NONE} (deterministic next-N-requests mode, no RNG).
 * </ul>
 *
 * <p>Per-route state mutates under a route-scoped lock so the RNG advance, the window countdown and
 * the invocation counter move together atomically.
 */
@Slf4j
@Component
public class FaultRegistry {

    /** Per-route mutable state. The {@link Random} is only created from a seeded profile. */
    private static final class RouteState {
        private FaultProfile profile;
        private Random random;
        private long remainingWindow;

        RouteState(FaultProfile profile) {
            install(profile);
        }

        void install(FaultProfile profile) {
            this.profile = profile;
            this.random = new Random(profile.seed());
            this.remainingWindow = profile.applyToNext();
        }
    }

    private final Map<PartnerRoute, RouteState> state = new EnumMap<>(PartnerRoute.class);

    public FaultRegistry() {
        for (PartnerRoute route : PartnerRoute.values()) {
            state.put(route, new RouteState(FaultProfile.none(route)));
        }
    }

    /** Replace a single route's profile, resetting its seeded RNG and deterministic window. */
    public void apply(FaultProfile profile) {
        RouteState routeState = state.get(profile.route());
        synchronized (routeState) {
            routeState.install(profile);
        }
        log.info(
                "fault applied route={} mode={} delayMs={} jitterMs={} probability={} applyToNext={} seed={}",
                profile.route(),
                profile.mode(),
                profile.delayMillis(),
                profile.jitterMillis(),
                profile.probability(),
                profile.applyToNext(),
                profile.seed());
    }

    /** Clear every route back to healthy ({@link FaultMode#NONE}). */
    public void resetAll() {
        for (PartnerRoute route : PartnerRoute.values()) {
            RouteState routeState = state.get(route);
            synchronized (routeState) {
                routeState.install(FaultProfile.none(route));
            }
        }
        log.info("fault profiles reset: all routes healthy");
    }

    /** The currently configured profile for a route (for the control-plane read endpoint). */
    public FaultProfile profileFor(PartnerRoute route) {
        RouteState routeState = state.get(route);
        synchronized (routeState) {
            return routeState.profile;
        }
    }

    /**
     * Resolve the effective decision for one request to {@code route}, advancing seeded RNG and any
     * deterministic window. Probabilistic modes are rolled here so callers see only a concrete mode.
     */
    public FaultDecision decide(PartnerRoute route) {
        RouteState routeState = state.get(route);
        synchronized (routeState) {
            FaultProfile profile = routeState.profile;
            if (profile.mode() == FaultMode.NONE) {
                return FaultDecision.normal();
            }

            if (profile.isDeterministicWindow()) {
                if (routeState.remainingWindow <= 0) {
                    // Window exhausted: auto-heal so the route returns to normal without a reset call.
                    routeState.install(FaultProfile.none(route));
                    return FaultDecision.normal();
                }
                routeState.remainingWindow--;
                return materialise(profile, routeState.random, true);
            }

            return materialise(profile, routeState.random, false);
        }
    }

    /**
     * Turn a profile into a concrete decision. {@code forced} is true inside a deterministic window
     * (the fault always fires); otherwise probabilistic modes roll the seeded RNG.
     */
    private FaultDecision materialise(FaultProfile profile, Random random, boolean forced) {
        return switch (profile.mode()) {
            case NONE -> FaultDecision.normal();
            case LATENCY, TIMEOUT, LATE_RESPONSE -> FaultDecision.delayed(
                    profile.mode(), resolveDelay(profile, random));
            case FAIL_BEFORE_RESPONSE, DUPLICATE -> FaultDecision.of(profile.mode());
            case DECLINE -> {
                if (forced || random.nextDouble() < profile.probability()) {
                    yield FaultDecision.decline(profile.declineReasonOpt().orElse("DECLINED_BY_PARTNER"));
                }
                yield FaultDecision.normal();
            }
            case ERROR_RATE -> {
                if (forced || random.nextDouble() < profile.probability()) {
                    yield FaultDecision.of(FaultMode.ERROR_RATE);
                }
                yield FaultDecision.normal();
            }
        };
    }

    /** Base delay plus a uniform jitter draw from the seeded RNG, so total delay is reproducible. */
    private long resolveDelay(FaultProfile profile, Random random) {
        if (profile.jitterMillis() <= 0) {
            return profile.delayMillis();
        }
        return profile.delayMillis() + random.nextLong(profile.jitterMillis() + 1);
    }
}
