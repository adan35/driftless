package io.driftless.app.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;
import java.util.Set;

/**
 * Makes the Driftless module map (CLAUDE.md) <em>provable in CI</em> rather than convention.
 *
 * <p>The {@code app} module depends on every feature module, so ArchUnit can import the whole {@code
 * io.driftless..} main surface from one place. The import excludes test classes ({@link
 * ImportOption.DoNotIncludeTests}); recon/observability test fixtures legitimately reach into other
 * modules' internals and must not register as violations.
 *
 * <p>The contract proven here:
 *
 * <ul>
 *   <li>no module reaches into another module's encapsulated ({@code internal}/{@code web}) packages
 *       — features integrate only through {@code api}/{@code spi} and the shared {@code common}
 *       kernel;
 *   <li>the dependency direction points strictly inward: {@code common} depends on nothing else and
 *       {@code ledger} depends only on {@code common};
 *   <li>there are no package cycles among {@code io.driftless} slices.
 * </ul>
 */
@AnalyzeClasses(packages = "io.driftless", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchTest {

    /**
     * Maps the first package segment under {@code io.driftless} to its owning Maven module. The
     * idempotency module publishes two top-level packages — {@code idempotency} (the guard) and
     * {@code outbox} (the transactional outbox) — that integrate freely with each other in-module, so
     * both map to {@code idempotency}.
     */
    private static final Map<String, String> SEGMENT_TO_MODULE = Map.ofEntries(
            Map.entry("common", "common"),
            Map.entry("ledger", "ledger"),
            Map.entry("idempotency", "idempotency"),
            Map.entry("outbox", "idempotency"),
            Map.entry("auth", "auth"),
            Map.entry("rules", "rules"),
            Map.entry("tokens", "tokens"),
            Map.entry("recon", "recon"),
            Map.entry("observability", "observability"),
            Map.entry("app", "app"));

    /**
     * The one intentional, documented cross-module reach into internals: the reconciliation/proof
     * harness ({@code recon}) drives the auth saga in-process — the deterministic path the zero-drift
     * property gate and demo runner exercise — via {@code auth.internal} (AuthorizationSaga,
     * AuthorizeCommand, RecoverySweep, AuthorizationRepository). It is a real boundary smell flagged
     * for follow-up (promote a saga facade into {@code auth.api}); until then it is pinned to exactly
     * this one edge so every <em>other</em> cross-module internal access still fails the build.
     */
    private static final Set<String> ALLOWED_INTERNAL_EDGES = Set.of("recon->auth");

    /** All inward targets other than {@code common} — used to assert the inward dependency direction. */
    private static final String[] LEDGER_AND_FEATURE_PACKAGES = {
        "io.driftless.ledger..",
        "io.driftless.idempotency..",
        "io.driftless.outbox..",
        "io.driftless.auth..",
        "io.driftless.rules..",
        "io.driftless.tokens..",
        "io.driftless.recon..",
        "io.driftless.observability..",
        "io.driftless.app.."
    };

    private static final String[] FEATURE_PACKAGES_ABOVE_LEDGER = {
        "io.driftless.idempotency..",
        "io.driftless.outbox..",
        "io.driftless.auth..",
        "io.driftless.rules..",
        "io.driftless.tokens..",
        "io.driftless.recon..",
        "io.driftless.observability..",
        "io.driftless.app.."
    };

    @ArchTest
    static final ArchRule modules_do_not_reach_into_foreign_internals =
            classes().that().resideInAPackage("io.driftless..").should(integrateThroughPublishedApiOnly());

    @ArchTest
    static final ArchRule common_kernel_depends_on_nothing_else = noClasses()
            .that()
            .resideInAPackage("io.driftless.common..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(LEDGER_AND_FEATURE_PACKAGES)
            .as("common (the kernel) must not depend on ledger or any feature module");

    @ArchTest
    static final ArchRule ledger_depends_only_on_common = noClasses()
            .that()
            .resideInAPackage("io.driftless.ledger..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FEATURE_PACKAGES_ABOVE_LEDGER)
            .as("ledger must depend only on common — the dependency direction points inward");

    @ArchTest
    static final ArchRule no_package_cycles =
            slices().matching("io.driftless.(*)..").should().beFreeOfCycles();

    private static ArchCondition<JavaClass> integrateThroughPublishedApiOnly() {
        return new ArchCondition<>(
                "integrate with other modules only through their api/spi packages, never their internal/web packages") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String sourceModule = moduleOf(source.getPackageName());
                if (sourceModule == null) {
                    return;
                }
                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    String targetModule = moduleOf(targetPackage);
                    if (targetModule == null || targetModule.equals(sourceModule) || "common".equals(targetModule)) {
                        continue;
                    }
                    if (!isEncapsulated(targetPackage)) {
                        continue;
                    }
                    if (ALLOWED_INTERNAL_EDGES.contains(sourceModule + "->" + targetModule)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(source, dependency.getDescription()));
                }
            }
        };
    }

    private static String moduleOf(String packageName) {
        if (packageName == null || !packageName.startsWith("io.driftless")) {
            return null;
        }
        String remainder = packageName.equals("io.driftless") ? "" : packageName.substring("io.driftless.".length());
        String segment = remainder.isEmpty() ? "" : remainder.split("\\.")[0];
        return SEGMENT_TO_MODULE.get(segment);
    }

    private static boolean isEncapsulated(String packageName) {
        for (String segment : packageName.split("\\.")) {
            if (segment.equals("internal") || segment.equals("web")) {
                return true;
            }
        }
        return false;
    }
}
