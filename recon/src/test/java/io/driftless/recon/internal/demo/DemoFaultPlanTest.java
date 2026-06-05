package io.driftless.recon.internal.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.recon.internal.fault.FaultKind;
import io.driftless.recon.internal.fault.FaultRequest;
import io.driftless.recon.internal.fault.FaultRoute;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure fault-mix mapping is deterministic and covers every supported mix. */
class DemoFaultPlanTest {

    @Test
    void noneAndNullAndBlankProduceNoFaults() {
        assertThat(DemoFaultPlan.forMix("NONE", 40, 7L)).isEmpty();
        assertThat(DemoFaultPlan.forMix(null, 40, 7L)).isEmpty();
        assertThat(DemoFaultPlan.forMix("  none  ", 40, 7L)).isEmpty();
    }

    @Test
    void timeoutInjectsABoundedTimeoutBreachOnAuthorize() {
        List<FaultRequest> plan = DemoFaultPlan.forMix("TIMEOUT", 40, 7L);
        assertThat(plan).hasSize(1);
        FaultRequest fault = plan.get(0);
        assertThat(fault.route()).isEqualTo(FaultRoute.AUTHORIZE);
        assertThat(fault.mode()).isEqualTo(FaultKind.TIMEOUT);
        assertThat(fault.delayMillis()).isEqualTo(DemoFaultPlan.TIMEOUT_DELAY_MILLIS);
        assertThat(fault.applyToNext()).isEqualTo(10L); // round(40 * 0.25)
    }

    @Test
    void failBeforeResponseInjectsThatModeOnAuthorize() {
        FaultRequest fault =
                DemoFaultPlan.forMix("fail_before_response", 40, 7L).get(0);
        assertThat(fault.mode()).isEqualTo(FaultKind.FAIL_BEFORE_RESPONSE);
        assertThat(fault.applyToNext()).isEqualTo(10L);
    }

    @Test
    void declineInjectsAProbabilisticDecline() {
        FaultRequest fault = DemoFaultPlan.forMix("DECLINE", 40, 7L).get(0);
        assertThat(fault.mode()).isEqualTo(FaultKind.DECLINE);
        assertThat(fault.probability()).isEqualTo(0.25);
        assertThat(fault.seed()).isEqualTo(7L);
    }

    @Test
    void duplicateInjectsADuplicateWindow() {
        FaultRequest fault = DemoFaultPlan.forMix("DUPLICATE", 40, 7L).get(0);
        assertThat(fault.mode()).isEqualTo(FaultKind.DUPLICATE);
        assertThat(fault.applyToNext()).isEqualTo(10L);
    }

    @Test
    void mixedBlendsTimeoutAndFailBeforeResponse() {
        List<FaultRequest> plan = DemoFaultPlan.forMix("MIXED", 40, 7L);
        assertThat(plan).hasSize(2);
        assertThat(plan)
                .extracting(FaultRequest::mode)
                .containsExactly(FaultKind.TIMEOUT, FaultKind.FAIL_BEFORE_RESPONSE);
        assertThat(plan.get(0).applyToNext()).isEqualTo(8L); // round(40 * 0.2)
        assertThat(plan.get(1).applyToNext()).isEqualTo(4L); // round(40 * 0.1)
    }

    @Test
    void shareIsAtLeastOneForTinyRuns() {
        assertThat(DemoFaultPlan.forMix("TIMEOUT", 1, 7L).get(0).applyToNext()).isEqualTo(1L);
    }

    @Test
    void unknownMixIsRejected() {
        assertThatThrownBy(() -> DemoFaultPlan.forMix("BOGUS", 40, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOGUS");
    }
}
