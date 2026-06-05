package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.recon.api.CheckResult;
import io.driftless.recon.api.Offender;
import io.driftless.recon.api.OffenderType;
import io.driftless.recon.api.RcaReport;
import io.driftless.recon.api.ReconCheck;
import io.driftless.recon.api.ReconciliationResult;
import io.driftless.recon.internal.RcaReporter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the detector actually works (not a no-op that always passes): a DELIBERATELY UNBALANCED
 * journal_entry row inserted DIRECTLY VIA SQL — bypassing the balanced {@code post()} the ledger
 * would reject — is detected, the offending account/transaction is named, and an RCA report is
 * produced. The ledger is never auto-edited to "fix" it.
 */
class DriftDetectionIT extends AbstractReconIT {

    @Autowired
    RcaReporter rcaReporter;

    @Test
    void detectsAnArtificiallyIntroducedUnbalancedPostingAndNamesTheOffender() {
        AccountId account = openFundedAccount(100_000);
        // Sanity: a correct ledger reconciles before we tamper with it.
        assertThat(reconciliationService.run().passed()).isTrue();
        long before = globalSignedSum();

        UUID offendingTx = injectUnbalancedEntry(account, 12_345);

        ReconciliationResult result = reconciliationService.run();

        assertThat(result.passed()).as("an unbalanced posting must be DETECTED").isFalse();
        assertThat(result.totalDriftMinor()).isGreaterThan(0L);

        CheckResult globalZero = result.checks().stream()
                .filter(c -> c.check() == ReconCheck.GLOBAL_ZERO)
                .findFirst()
                .orElseThrow();
        assertThat(globalZero.passed()).isFalse();
        assertThat(globalZero.driftMinor()).isEqualTo(Math.abs(before + 12_345L));

        // The offending transaction AND its account are named for RCA.
        assertThat(result.offenders())
                .anyMatch(o ->
                        o.type() == OffenderType.TRANSACTION && o.reference().equals(offendingTx.toString()));
        assertThat(result.offenders())
                .anyMatch(o -> o.type() == OffenderType.ACCOUNT && o.reference().equals(account.toString()));

        // The raw ledger really did drift — assert the DELTA the injection introduced (robust to any
        // shared-DB starting balance), not an absolute figure.
        assertThat(globalSignedSum() - before).isEqualTo(12_345L);
    }

    @Test
    void perTransactionCatchesTwoUnbalancedTransactionsThatOffsetGlobally() {
        AccountId debitAccount = openFundedAccount(100_000);
        AccountId creditAccount = openFundedAccount(100_000);
        assertThat(reconciliationService.run().passed()).isTrue();
        long before = globalSignedSum();

        // Two unbalanced transactions whose drifts cancel in the GLOBAL sum (+7_000 and -7_000) but
        // that each individually fail to net to zero.
        UUID debitTx = injectUnbalancedEntry(debitAccount, 7_000);
        UUID creditTx = injectUnbalancedCredit(creditAccount, 7_000);

        // GLOBAL_ZERO is fooled — the two offset — but PER_TRANSACTION is strictly stronger.
        assertThat(globalSignedSum() - before)
                .as("the two injections offset globally")
                .isZero();

        ReconciliationResult result = reconciliationService.run();
        assertThat(result.passed()).as("PER_TRANSACTION must fail the run").isFalse();

        CheckResult globalZero = result.checks().stream()
                .filter(c -> c.check() == ReconCheck.GLOBAL_ZERO)
                .findFirst()
                .orElseThrow();
        assertThat(globalZero.passed())
                .as("GLOBAL_ZERO passes because the imbalances cancel globally")
                .isTrue();

        CheckResult perTx = result.checks().stream()
                .filter(c -> c.check() == ReconCheck.PER_TRANSACTION)
                .findFirst()
                .orElseThrow();
        assertThat(perTx.passed())
                .as("PER_TRANSACTION catches each offending transaction")
                .isFalse();
        assertThat(perTx.driftMinor()).isEqualTo(14_000L);

        // BOTH offending transactions are named.
        assertThat(result.offenders())
                .anyMatch(o ->
                        o.type() == OffenderType.TRANSACTION && o.reference().equals(debitTx.toString()));
        assertThat(result.offenders())
                .anyMatch(o ->
                        o.type() == OffenderType.TRANSACTION && o.reference().equals(creditTx.toString()));
    }

    @Test
    void producesAnRcaReportFromAFailedReconciliation() {
        AccountId account = openFundedAccount(100_000);
        long before = globalSignedSum();
        injectUnbalancedEntry(account, 9_900);
        ReconciliationResult result = reconciliationService.run();
        assertThat(result.passed()).isFalse();

        RcaReport report = rcaReporter.report(result);

        assertThat(report.reconciliationId()).isEqualTo(result.id());
        assertThat(report.drifted()).isTrue();
        assertThat(report.totalDriftMinor()).isEqualTo(result.totalDriftMinor());
        assertThat(report.failedChecks()).contains(ReconCheck.GLOBAL_ZERO);
        assertThat(report.candidateCause()).containsIgnoringCase("unbalanced");
        assertThat(report.offenders())
                .extracting(Offender::type)
                .contains(OffenderType.TRANSACTION, OffenderType.ACCOUNT);
        assertThat(report.summary()).contains(result.id().toString());

        // Reconciliation NEVER auto-edits the ledger to fix drift: assert the DELTA it left behind.
        assertThat(globalSignedSum() - before).isEqualTo(9_900L);
    }

    @Test
    void rcaReportFromAPassingRunStatesNoDrift() {
        openFundedAccount(50_000);
        ReconciliationResult clean = reconciliationService.run();

        RcaReport report = rcaReporter.report(clean);

        assertThat(report.drifted()).isFalse();
        assertThat(report.candidateCause()).isEqualTo("no drift");
        assertThat(report.summary()).containsIgnoringCase("PASSED");
    }
}
