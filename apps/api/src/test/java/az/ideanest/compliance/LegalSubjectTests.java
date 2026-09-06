package az.ideanest.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.compliance.domain.MalformedTaxIdentifierException;
import az.ideanest.compliance.domain.TaxIdentifier;
import az.ideanest.shared.compliance.LegalSubject;
import az.ideanest.shared.compliance.SubjectKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The legal subject's own rules — issue #430.
 *
 * <p>A unit test, with no database and no container: completeness, the name match and the VÖEN
 * shape are pure functions, and starting PostgreSQL to assert on one is the thing
 * {@code AbstractIntegrationTest} tells callers not to do.
 */
class LegalSubjectTests {

    @Nested
    @DisplayName("completeness")
    class Completeness {

        @Test
        @DisplayName("an individual is complete as soon as they have a name")
        void individualNeedsOnlyAName() {
            assertThat(LegalSubject.individual("Aygün Məmmədova").isComplete()).isTrue();
        }

        @Test
        @DisplayName("a legal entity is not complete until it can be identified and found")
        void legalEntityNeedsAllThree() {
            // "A company called that" is not a party an indemnity clause can be enforced
            // against, which is the whole reason the three fields exist.
            LegalSubject named = new LegalSubject(SubjectKind.LEGAL_ENTITY, "Nümunə MMC", null, null, null);
            assertThat(named.isComplete()).isFalse();

            LegalSubject partial =
                    new LegalSubject(SubjectKind.LEGAL_ENTITY, "Nümunə MMC", "1234567890", "Bakı, Nizami 1", null);
            assertThat(partial.isComplete()).isFalse();

            LegalSubject whole = new LegalSubject(
                    SubjectKind.LEGAL_ENTITY, "Nümunə MMC", "1234567890", "Bakı, Nizami 1", "AZ-1234");
            assertThat(whole.isComplete()).isTrue();
        }

        @Test
        @DisplayName("completeness is not verification, and says so by accepting an invention")
        void completeIsNotVerified() {
            // The point of the assertion is what it does NOT prove. A creator may type any
            // company's details in and this returns true; whether the number names a real
            // company is a human reading a registration extract, in #431's queue.
            LegalSubject invented = new LegalSubject(
                    SubjectKind.LEGAL_ENTITY, "Somebody Else LLC", "0000000000", "Nowhere 1", "X-1");
            assertThat(invented.isComplete()).isTrue();
        }
    }

    @Nested
    @DisplayName("the name match")
    class NameMatching {

        private final LegalSubject subject = LegalSubject.individual("Aygün Məmmədova");

        @Test
        @DisplayName("presentation differs between a certificate, a bank and a form, and that is not a mismatch")
        void forgivingAboutPresentation() {
            assertThat(subject.nameMatches("aygün məmmədova")).isTrue();
            assertThat(subject.nameMatches("  Aygün   Məmmədova ")).isTrue();
            assertThat(subject.nameMatches("AYGÜN MƏMMƏDOVA")).isTrue();
        }

        @Test
        @DisplayName("a different name is a mismatch, and a missing patronymic is a different name")
        void strictAboutIdentity() {
            // Deliberately not fuzzy. A missing patronymic or a transliterated surname belongs
            // in front of a human as MISMATCHED_NAME, not resolved by a similarity score.
            assertThat(subject.nameMatches("Aygün Əli qızı Məmmədova")).isFalse();
            assertThat(subject.nameMatches("Aygun Mammadova")).isFalse();
            assertThat(subject.nameMatches("Elvin Məmmədov")).isFalse();
            assertThat(subject.nameMatches(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("the VÖEN")
    class TaxIdentifiers {

        @Test
        @DisplayName("ten digits, and punctuation somebody copied off a certificate is stripped")
        void shapeOnly() {
            assertThat(TaxIdentifier.normalise("1234567890")).isEqualTo("1234567890");
            assertThat(TaxIdentifier.normalise(" 1234-567-890 ")).isEqualTo("1234567890");
        }

        @Test
        @DisplayName("anything that is not ten digits is refused, and the refusal repeats what was typed")
        void refusesEverythingElse() {
            assertThatThrownBy(() -> TaxIdentifier.normalise("12345"))
                    .isInstanceOf(MalformedTaxIdentifierException.class)
                    .hasMessageContaining("12345");
            assertThatThrownBy(() -> TaxIdentifier.normalise("12345678901"))
                    .isInstanceOf(MalformedTaxIdentifierException.class);
            assertThatThrownBy(() -> TaxIdentifier.normalise("ABCDEFGHIJ"))
                    .isInstanceOf(MalformedTaxIdentifierException.class);
            assertThatThrownBy(() -> TaxIdentifier.normalise(null))
                    .isInstanceOf(MalformedTaxIdentifierException.class);
        }

        @Test
        @DisplayName("SHAPE ONLY: a number that is not any company's is accepted")
        void deliberatelyDoesNotConfirmExistence() {
            // Named loudly because the absence is the design. #430: a validator that appeared
            // to confirm existence "would produce a green tick that means nothing, in front of
            // the exact field where a green tick is relied upon".
            //
            // There is no checksum either. TaxIdentifier's docblock says why: an algorithm
            // implemented from a guess rejects valid numbers, and a field that refuses a
            // company's real VÖEN is caught by nobody, because the creator concludes the
            // platform is broken and leaves.
            assertThat(TaxIdentifier.normalise("0000000000")).isEqualTo("0000000000");
            assertThat(TaxIdentifier.isWellShaped("9999999999")).isTrue();
        }
    }

    @Test
    @DisplayName("an individual carries no entity fields, whatever was passed")
    void individualIsBare() {
        LegalSubject person = LegalSubject.individual("Aygün Məmmədova");
        assertThat(person.taxIdentifier()).isEmpty();
        assertThat(person.address()).isEmpty();
        assertThat(person.registration()).isEmpty();
    }

    @Test
    @DisplayName("a blank field is absent rather than empty, so a caller has one thing to check")
    void blanksAreNulls() {
        LegalSubject subject = new LegalSubject(SubjectKind.LEGAL_ENTITY, "Nümunə MMC", "   ", "", null);
        assertThat(subject.taxIdentifier()).isEmpty();
        assertThat(subject.address()).isEmpty();
    }

    @Test
    @DisplayName("a subject has a name")
    void nameIsRequired() {
        assertThatThrownBy(() -> new LegalSubject(SubjectKind.INDIVIDUAL, "  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
