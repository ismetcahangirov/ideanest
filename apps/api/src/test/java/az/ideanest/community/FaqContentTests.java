package az.ideanest.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.community.domain.FaqContent;
import az.ideanest.community.domain.FaqContentInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What an FAQ entry may say.
 *
 * <p>No database and no HTTP: {@link FaqContent} is a pure type, and asserting on a
 * table of rules through a request that also had to authenticate, authorise, and open a
 * transaction would make each of these tests fifty times slower and its failures fifty
 * times less specific.
 *
 * <p>The same bounds are check constraints in V47, and {@code ProjectFaqSchemaTests}
 * asserts those separately. Neither file makes the other redundant: this one is what a
 * creator is told, and the constraint is what a bulk import is refused.
 */
class FaqContentTests {

    @Nested
    @DisplayName("question")
    class Questions {

        @Test
        @DisplayName("is stored trimmed")
        void isTrimmed() {
            // A question with leading whitespace renders misaligned in the list it
            // appears in, and the whitespace came from a paste.
            assertThat(FaqContent.question("  When do you ship?  ")).isEqualTo("When do you ship?");
        }

        @Test
        @DisplayName("cannot be blank")
        void cannotBeBlank() {
            // Whitespace only, not merely empty. A question of eighty spaces satisfies
            // "not null" and is not a question.
            assertThatThrownBy(() -> FaqContent.question("   "))
                    .isInstanceOf(FaqContentInvalidException.class)
                    .extracting(exception -> ((FaqContentInvalidException) exception).field())
                    .isEqualTo("question");
        }

        @Test
        @DisplayName("cannot be newlines, which one-argument btrim would have accepted")
        void cannotBeNewlines() {
            // The exact case V47's comment is about: PostgreSQL's btrim removes spaces
            // and nothing else, so this passes char_length(btrim(...)) > 0. Java's
            // isBlank and the regexp class in the constraint both refuse it, and this
            // asserts the Java half.
            assertThatThrownBy(() -> FaqContent.question("\n\n")).isInstanceOf(FaqContentInvalidException.class);
        }

        @Test
        @DisplayName("cannot be null")
        void cannotBeNull() {
            // Which is also what an explicit `"question": null` in a PATCH body means:
            // there is no way to clear a question, so the two are one refusal.
            assertThatThrownBy(() -> FaqContent.question(null)).isInstanceOf(FaqContentInvalidException.class);
        }

        @Test
        @DisplayName("is measured after trimming, so trailing whitespace does not use up the budget")
        void isMeasuredAfterTrimming() {
            String atTheLimit = "q".repeat(FaqContent.MAX_QUESTION_LENGTH);
            assertThat(FaqContent.question(atTheLimit + "   ")).isEqualTo(atTheLimit);
        }

        @Test
        @DisplayName("stops one character past the limit, and says what the limit is")
        void stopsPastTheLimit() {
            assertThatThrownBy(() -> FaqContent.question("q".repeat(FaqContent.MAX_QUESTION_LENGTH + 1)))
                    .isInstanceOf(FaqContentInvalidException.class)
                    .hasMessageContaining(String.valueOf(FaqContent.MAX_QUESTION_LENGTH));
        }

        @Test
        @DisplayName("counts an emoji as one character, as PostgreSQL and the editor's counter do")
        void countsCodePoints() {
            // A surrogate pair is two Java chars and one character everywhere a person
            // can see. If this counted chars, an entry the editor showed as being
            // inside the limit would be refused by the server.
            String emoji = "🚀";
            assertThat(FaqContent.question(emoji.repeat(FaqContent.MAX_QUESTION_LENGTH)))
                    .isEqualTo(emoji.repeat(FaqContent.MAX_QUESTION_LENGTH));
        }
    }

    @Nested
    @DisplayName("answer")
    class Answers {

        @Test
        @DisplayName("is stored trimmed")
        void isTrimmed() {
            assertThat(FaqContent.answer("  In March.  ")).isEqualTo("In March.");
        }

        @Test
        @DisplayName("cannot be blank, because an unanswered question on a public page is worse than none")
        void cannotBeBlank() {
            assertThatThrownBy(() -> FaqContent.answer(" \t "))
                    .isInstanceOf(FaqContentInvalidException.class)
                    .extracting(exception -> ((FaqContentInvalidException) exception).field())
                    .isEqualTo("answer");
        }

        @Test
        @DisplayName("cannot be null")
        void cannotBeNull() {
            assertThatThrownBy(() -> FaqContent.answer(null)).isInstanceOf(FaqContentInvalidException.class);
        }

        @Test
        @DisplayName("stops one character past the limit")
        void stopsPastTheLimit() {
            assertThatThrownBy(() -> FaqContent.answer("a".repeat(FaqContent.MAX_ANSWER_LENGTH + 1)))
                    .isInstanceOf(FaqContentInvalidException.class)
                    .hasMessageContaining(String.valueOf(FaqContent.MAX_ANSWER_LENGTH));
        }

        @Test
        @DisplayName("accepts an answer exactly at the limit")
        void acceptsTheLimit() {
            // The boundary from the other side. A test that only asserted the refusal
            // would pass just as well against a type that refused everything.
            String atTheLimit = "a".repeat(FaqContent.MAX_ANSWER_LENGTH);
            assertThat(FaqContent.answer(atTheLimit)).hasSize(FaqContent.MAX_ANSWER_LENGTH);
        }
    }

    @Test
    @DisplayName("the two fields are bounded differently, and the message says which one was refused")
    void theFieldsAreNamedSeparately() {
        // A question is a sentence and an answer is a paragraph. The messages have to
        // be told apart because the editor shows each beside its own input.
        assertThat(FaqContent.MAX_QUESTION_LENGTH).isLessThan(FaqContent.MAX_ANSWER_LENGTH);
        assertThatThrownBy(() -> FaqContent.answer(""))
                .isInstanceOf(FaqContentInvalidException.class)
                .extracting(exception -> ((FaqContentInvalidException) exception).field())
                .isEqualTo("answer");
    }
}
