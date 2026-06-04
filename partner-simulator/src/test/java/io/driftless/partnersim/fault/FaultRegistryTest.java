package io.driftless.partnersim.fault;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-level proof of the determinism contract: probabilistic modes are reproducible when seeded and
 * hit their configured probability over N requests; deterministic windows fire exactly N times.
 */
class FaultRegistryTest {

    private static final int SAMPLES = 2000;

    private List<FaultMode> drive(FaultProfile profile, int requests) {
        FaultRegistry registry = new FaultRegistry();
        registry.apply(profile);
        List<FaultMode> outcomes = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            outcomes.add(registry.decide(profile.route()).mode());
        }
        return outcomes;
    }

    @Test
    void errorRateIsReproducibleForAGivenSeed() {
        FaultProfile profile = new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.ERROR_RATE, 0, 0, 0.3, 0, 42, null);

        List<FaultMode> runA = drive(profile, SAMPLES);
        List<FaultMode> runB = drive(profile, SAMPLES);

        // Same seed -> identical outcome sequence: this is what makes CI assertions reproducible.
        assertThat(runA).isEqualTo(runB);
    }

    @Test
    void errorRateHitsConfiguredProbabilityWithinTolerance() {
        double probability = 0.3;
        FaultProfile profile =
                new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.ERROR_RATE, 0, 0, probability, 0, 123, null);

        long errors = drive(profile, SAMPLES).stream()
                .filter(mode -> mode == FaultMode.ERROR_RATE)
                .count();
        double observed = (double) errors / SAMPLES;

        assertThat(observed).isCloseTo(probability, org.assertj.core.data.Offset.offset(0.03));
    }

    @Test
    void declineHitsConfiguredProbabilityWithinTolerance() {
        double probability = 0.5;
        FaultProfile profile =
                new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.DECLINE, 0, 0, probability, 0, 99, "NO");

        long declines = drive(profile, SAMPLES).stream()
                .filter(mode -> mode == FaultMode.DECLINE)
                .count();
        double observed = (double) declines / SAMPLES;

        assertThat(observed).isCloseTo(probability, org.assertj.core.data.Offset.offset(0.03));
    }

    @Test
    void differentSeedsProduceDifferentSequences() {
        FaultProfile seedA = new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.ERROR_RATE, 0, 0, 0.5, 0, 1, null);
        FaultProfile seedB = new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.ERROR_RATE, 0, 0, 0.5, 0, 2, null);

        assertThat(drive(seedA, SAMPLES)).isNotEqualTo(drive(seedB, SAMPLES));
    }

    @Test
    void deterministicWindowFiresExactlyNextNThenHeals() {
        FaultProfile profile = new FaultProfile(PartnerRoute.CAPTURE, FaultMode.ERROR_RATE, 0, 0, 1.0, 3, 0, null);
        FaultRegistry registry = new FaultRegistry();
        registry.apply(profile);

        // First 3 fire deterministically, then it auto-heals to NONE.
        assertThat(registry.decide(PartnerRoute.CAPTURE).mode()).isEqualTo(FaultMode.ERROR_RATE);
        assertThat(registry.decide(PartnerRoute.CAPTURE).mode()).isEqualTo(FaultMode.ERROR_RATE);
        assertThat(registry.decide(PartnerRoute.CAPTURE).mode()).isEqualTo(FaultMode.ERROR_RATE);
        assertThat(registry.decide(PartnerRoute.CAPTURE).mode()).isEqualTo(FaultMode.NONE);
        assertThat(registry.decide(PartnerRoute.CAPTURE).mode()).isEqualTo(FaultMode.NONE);
    }

    @Test
    void latencyDelayWithJitterIsReproducibleForAGivenSeed() {
        FaultProfile profile = new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.LATENCY, 100, 50, 0.0, 0, 555, null);

        FaultRegistry registryA = new FaultRegistry();
        registryA.apply(profile);
        FaultRegistry registryB = new FaultRegistry();
        registryB.apply(profile);

        for (int i = 0; i < 20; i++) {
            long delayA = registryA.decide(PartnerRoute.AUTHORIZE).delayMillis();
            long delayB = registryB.decide(PartnerRoute.AUTHORIZE).delayMillis();
            assertThat(delayA).isEqualTo(delayB);
            assertThat(delayA).isBetween(100L, 150L);
        }
    }

    @Test
    void noFaultYieldsNormalDecision() {
        FaultRegistry registry = new FaultRegistry();
        assertThat(registry.decide(PartnerRoute.AUTHORIZE).mode()).isEqualTo(FaultMode.NONE);
        assertThat(registry.profileFor(PartnerRoute.AUTHORIZE).mode()).isEqualTo(FaultMode.NONE);
    }

    @Test
    void resetClearsActiveProfiles() {
        FaultRegistry registry = new FaultRegistry();
        registry.apply(new FaultProfile(PartnerRoute.REVERSE, FaultMode.ERROR_RATE, 0, 0, 1.0, 0, 1, null));
        assertThat(registry.profileFor(PartnerRoute.REVERSE).mode()).isEqualTo(FaultMode.ERROR_RATE);

        registry.resetAll();

        assertThat(registry.profileFor(PartnerRoute.REVERSE).mode()).isEqualTo(FaultMode.NONE);
        assertThat(registry.decide(PartnerRoute.REVERSE).mode()).isEqualTo(FaultMode.NONE);
    }
}
