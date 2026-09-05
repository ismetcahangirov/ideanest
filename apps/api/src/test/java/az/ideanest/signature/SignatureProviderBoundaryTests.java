package az.ideanest.signature;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.signature.domain.SignatureProvider;
import az.ideanest.support.ProductionClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §9.4's last sentence, applied to signatures and checked rather than described — issue #428.
 *
 * <p>"No provider SDK is called anywhere except behind this interface. Changing provider must be
 * a single-file change."
 *
 * <p><strong>Not a style preference.</strong> SİMA İmza is one national provider today and ASAN
 * İmza is the obvious second; a second integration is only a day's work if the first one's
 * vocabulary never leaked. The moment a SİMA session identifier or status string reaches the
 * module that submits a campaign, the second provider becomes a rewrite of everything that
 * touched it.
 *
 * <p>{@code PaymentProviderBoundaryTests} makes the same two assertions about charges, and
 * {@code ModuleBoundaryTests} makes the module-boundary one about §16.1. This is the third
 * instance of one argument: a rule that lives only in a comment survives until the first
 * afternoon somebody is in a hurry.
 */
class SignatureProviderBoundaryTests {

    /**
     * One import, shared with every other ArchUnit suite — see {@link ProductionClasses}.
     *
     * <p>Each suite used to build its own, and five identical object graphs alive at once in one
     * test worker is what turned the {@code test} task into an {@code OutOfMemoryError} with no
     * test failing.
     */
    private static final JavaClasses PRODUCTION_CLASSES = ProductionClasses.get();

    /** The one adapter #428 ships, named so that a second one appearing is a failing test. */
    private static final String SIMA = "az.ideanest.signature.infrastructure.SimaImzaSignatureProvider";

    @Test
    @DisplayName("there are classes to check")
    void classesWereImported() {
        assertThat(PRODUCTION_CLASSES).isNotEmpty();
    }

    /**
     * <strong>Exactly one adapter is on the classpath, and it is the SİMA one.</strong>
     *
     * <p>Where {@code PaymentProviderBoundaryTests} asserts that <em>no</em> adapter ships, this
     * asserts <em>which</em> one does — and the difference between the two assertions is the
     * difference between the two situations. §9.2 refuses a payment stub because one that
     * returned an approval "would make this path look finished and would have told clients that
     * cards were verified when no card was ever seen". {@code SimaImzaSignatureProvider} is not
     * a stub: it speaks to SİMA's real sandbox, which issues real signatures against test
     * certificates. What keeps it from doing anything unintended is configuration — the provider
     * property is blank by default, and {@code SignatureProviders} refuses outright to start
     * against production SİMA until #423 answers — rather than absence.
     *
     * <p>So the friction this test creates is aimed at the thing that would actually be wrong: a
     * convenient fake, added to get a demo working, that reports a signature nobody made. The
     * second adapter that legitimately belongs here is ASAN İmza, and whoever writes it adds its
     * name below in the same change.
     */
    @Test
    @DisplayName("SİMA İmza is the only signature adapter shipped")
    void onlyTheSimaAdapterShips() {
        List<String> implementations = PRODUCTION_CLASSES.stream()
                .filter(candidate -> candidate.isAssignableTo(SignatureProvider.class))
                .filter(candidate -> !candidate.isInterface())
                .map(JavaClass::getName)
                .toList();

        assertThat(implementations)
                .withFailMessage(
                        "The signature adapters on the production classpath are %s.%n"
                                + "#428 ships one, against SİMA's sandbox. A second real provider is a"
                                + " welcome change — add it here in the same commit. A fake that reports a"
                                + " signature nobody made is not: it would tell a court the platform holds"
                                + " a citizen's signature when it holds nothing.",
                        String.join(", ", implementations))
                .containsExactly(SIMA);
    }

    /**
     * The provider's vocabulary stays inside the signature module.
     *
     * <p>Stated as "nothing outside {@code az.ideanest.signature} names any of these types",
     * which is the checkable form of "a provider change is a single-file change": if the legal
     * module or a controller could hold a {@code SignatureResult}, then changing what a provider
     * answers would change them too.
     *
     * <p><strong>Today the rule is satisfied trivially, and that is #428's own definition of
     * done</strong> — "nothing outside the {@code signature} module references any of it yet;
     * #429 is the caller". It is asserted now rather than when the first caller arrives, because
     * the first caller is exactly when somebody would reach for a {@code SignatureOutcome} in a
     * controller.
     */
    @Test
    @DisplayName("no module outside signature names a provider request or result type")
    void theProvidersVocabularyStaysInsideTheModule() {
        noClasses()
                .that()
                .resideOutsideOfPackage("az.ideanest.signature..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("az.ideanest.signature.domain..")
                .because("§9.4: a provider change must be a single-file change, which it cannot be if"
                        + " another module holds a SignatureResult or a SignatureProviderName")
                .check(PRODUCTION_CLASSES);
    }
}
