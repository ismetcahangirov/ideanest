package az.ideanest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The module boundary, checked rather than described.
 *
 * <p>{@code az/ideanest/package-info.java} states the rules. A rule that lives
 * only in a comment survives until the first afternoon somebody is in a hurry,
 * and the cost of finding out later is that extracting a module stops being a
 * contained piece of work.
 */
class ModuleBoundaryTests {

    private static final String ROOT = "az.ideanest";

    /**
     * Cross-cutting by definition: money, identifiers, the outbox, idempotency,
     * auditing. Everything may depend on it, which is exactly why nothing that
     * belongs to one feature is allowed to be put here.
     */
    private static final String SHARED = "shared";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    @DisplayName("there are classes to check")
    void classesWereImported() {
        assertThat(PRODUCTION_CLASSES).isNotEmpty();
    }

    @Test
    @DisplayName("a module does not reach into another module's internals")
    void modulesDoNotReachIntoEachOther() {
        List<String> violations = new ArrayList<>();

        for (JavaClass source : PRODUCTION_CLASSES) {
            String sourceModule = moduleOf(source.getPackageName());
            if (sourceModule == null) {
                continue;
            }
            for (JavaClass target : source.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass())
                    .toList()) {
                String targetPackage = target.getPackageName();
                String targetModule = moduleOf(targetPackage);
                if (targetModule == null || targetModule.equals(sourceModule) || SHARED.equals(targetModule)) {
                    continue;
                }
                boolean internal = targetPackage.contains(".domain") || targetPackage.contains(".infrastructure");
                if (internal) {
                    violations.add("%s reaches into %s".formatted(source.getName(), target.getName()));
                }
            }
        }

        // Reaching another module's domain or infrastructure couples the two to
        // each other's internals. Modules talk through their application layer,
        // which is the only part either side agreed to keep stable.
        assertThat(violations)
                .withFailMessage(
                        "A module reached into another module's internals:%n  %s%n"
                                + "Modules talk through their application layer.",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("domain does not depend on infrastructure or api")
    void domainStaysIndependentOfItsPlumbing() {
        // The rules of the business do not know they are stored in PostgreSQL or
        // reached over HTTP. When they start to, they can no longer be tested
        // without both.
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..infrastructure..", "..api..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("no cycles between modules")
    void modulesAreAcyclic() {
        // Two modules that depend on each other are one module with a naming
        // convention, and neither can be extracted or reasoned about alone.
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(PRODUCTION_CLASSES);
    }

    /** The module a package belongs to, or null for the root package itself. */
    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT + ".")) {
            return null;
        }
        String remainder = packageName.substring(ROOT.length() + 1);
        int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }
}
