package az.ideanest.support;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * {@code az.ideanest}, imported once for every ArchUnit suite in the build.
 *
 * <h2>Why this exists, and it is a memory bound rather than a tidiness preference</h2>
 *
 * <p>Five suites assert something about the shape of this service —
 * {@code ModuleBoundaryTests}, {@code PaymentProviderBoundaryTests},
 * {@code SignatureProviderBoundaryTests}, {@code JobTriggerTests} and
 * {@code ProjectStateMachineTests} — and each of them used to build its own
 * {@link JavaClasses} from an identical importer. ArchUnit's import is a full object graph of
 * every class, every member and every dependency in the package, and five of them are five
 * copies alive at once in one test JVM, because each is held in a {@code static final} field
 * that lives until the worker exits.
 *
 * <p>That was survivable at four and stopped being survivable at five: adding
 * {@code SignatureProviderBoundaryTests} for #428 turned the whole {@code test} task into an
 * {@code OutOfMemoryError} inside {@code ClassFileProcessor}, with no test failing — the worker
 * simply died. Gradle sets no {@code maxHeapSize} for this task, so the ceiling is a quarter of
 * whatever machine happens to be running it, which makes the failure look like a flake on one
 * developer's laptop and a hard failure on a smaller CI runner.
 *
 * <p>One import serves all five. It is built on first use and never rebuilt, which is safe
 * because {@link JavaClasses} is immutable and every rule reads it without modifying it.
 *
 * <h2>Every caller wants exactly this configuration, and that is checked rather than assumed</h2>
 *
 * <p>All five importers were character-for-character the same:
 * {@code DO_NOT_INCLUDE_TESTS} over {@code az.ideanest}. That is not a coincidence — it is what
 * "a rule about the production code" means — so the configuration belongs in one place where a
 * later suite cannot quietly ask a different question and compare its answer with everybody
 * else's.
 *
 * <p><strong>{@code DO_NOT_INCLUDE_TESTS} is load-bearing in both directions.</strong> Without
 * it, a test that legitimately names a {@code ChargeResult} in order to assert something about
 * it would itself violate the rule that nothing outside the payment module may — and the suite
 * would fail on its own existence.
 */
public final class ProductionClasses {

    /**
     * Lazily built and held for the life of the worker.
     *
     * <p>Lazy rather than eager because a run filtered to one suite that needs none of this —
     * {@code ./gradlew test --tests 'az.ideanest.legal.*'} — should not pay for an import
     * nothing reads. Not synchronised: JUnit's default is one test worker, and the worst a
     * second thread could do is build a second graph that is discarded, which is the cost this
     * class exists to avoid rather than a correctness problem.
     */
    private static JavaClasses production;

    private ProductionClasses() {
    }

    /** Everything under {@code az.ideanest} except the tests. */
    public static JavaClasses get() {
        if (production == null) {
            production = new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("az.ideanest");
        }
        return production;
    }
}
