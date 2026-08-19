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

    /**
     * The one class #236 is about: the enum that decides every capability check.
     *
     * <p>Named as a string rather than imported, so that the rule below cannot be
     * satisfied by this test file moving with it.
     */
    private static final String CAPABILITY = "az.ideanest.project.domain.Capability";

    /** Where the vocabulary is published, and the only sanctioned way to name one. */
    private static final String PROJECT_CAPABILITY = "az.ideanest.shared.access.ProjectCapability";

    /** Staff identity, published for the same reason and asked the same way. */
    private static final String PLATFORM_STAFF = "az.ideanest.shared.access.PlatformStaff";

    /**
     * The one #245 is about: who a message about a campaign goes to, when the answer is rows
     * another module owns.
     */
    private static final String PROJECT_AUDIENCES = "az.ideanest.shared.audience.ProjectAudiences";

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
    @DisplayName("no module names the project module's capability enum, and the contract is why they need not")
    void capabilitiesCrossOnlyThroughTheSharedContract() {
        // The rule that produced the defect #236 fixed, and it is kept. Four modules
        // wanted a fine-grained permission check, could not name this enum, and each
        // settled for the coarsest question the project module happened to publish —
        // so a collaborator granted only EDIT_REWARDS could publish a project update
        // and read the referral report. The answer is not to relax this; it is that
        // the vocabulary is published separately, which the second half asserts.
        noClasses()
                .that()
                .resideOutsideOfPackage("az.ideanest.project..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(CAPABILITY)
                .because("the capability vocabulary crosses as " + PROJECT_CAPABILITY + ", never as " + CAPABILITY)
                .check(PRODUCTION_CLASSES);

        // And the route is real rather than merely available: these are the four call
        // sites of #236, and a regression to the coarse check drops the module out of
        // this list rather than passing quietly.
        assertThat(modulesNaming(PROJECT_CAPABILITY))
                .withFailMessage(
                        "A module stopped asking for a named capability. Expected reward, community and"
                                + " analytics to name %s; found %s.",
                        PROJECT_CAPABILITY, modulesNaming(PROJECT_CAPABILITY))
                .contains("reward", "community", "analytics");
        assertThat(modulesNaming(PLATFORM_STAFF)).contains("moderation");
    }

    @Test
    @DisplayName("a computed audience crosses only through the shared contract")
    void audiencesCrossOnlyThroughTheSharedContract() {
        // #245's shape, and the same one #236 arrived at. §4.10 has notifications whose
        // audience is a list the platform computes -- a campaign's backers -- and the
        // notification module cannot compute one without reading `pledges`. The wrong
        // answers were available and both are worse: reach into the pledge module, which
        // the rule above forbids, or put the whole audience in the event, which is ten
        // thousand identifiers in a message.
        assertThat(modulesNaming(PROJECT_AUDIENCES))
                .withFailMessage(
                        "The notification module stopped asking for a published audience. Expected it to name"
                                + " %s; found %s.",
                        PROJECT_AUDIENCES, modulesNaming(PROJECT_AUDIENCES))
                .contains("notification");

        // And the implementation is the pledge module's, because `pledges` is its table.
        // If `shared` ever answered this itself, `shared` would have acquired a feature.
        assertThat(modulesNaming(PROJECT_AUDIENCES)).contains("pledge");
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

    /** Which modules depend on a published contract type, by simple module name. */
    private static List<String> modulesNaming(String contractType) {
        List<String> modules = new ArrayList<>();
        for (JavaClass source : PRODUCTION_CLASSES) {
            String module = moduleOf(source.getPackageName());
            if (module == null || SHARED.equals(module) || modules.contains(module)) {
                continue;
            }
            boolean names = source.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> dependency.getTargetClass().getName().equals(contractType));
            if (names) {
                modules.add(module);
            }
        }
        return modules;
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
