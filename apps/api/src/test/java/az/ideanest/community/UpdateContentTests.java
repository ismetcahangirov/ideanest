package az.ideanest.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.community.domain.UpdateContent;
import az.ideanest.community.domain.UpdateContentInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

/**
 * What an update may say.
 *
 * <p>No database and no HTTP: {@link UpdateContent} is a pure type, and asserting on a
 * table of rules through a request that also had to authenticate, authorise, and open a
 * transaction would make each of these tests fifty times slower and its failures fifty
 * times less specific.
 */
class UpdateContentTests {

    @Nested
    @DisplayName("title")
    class Titles {

        @Test
        @DisplayName("is stored trimmed")
        void isTrimmed() {
            // A title with leading whitespace renders as one that is misaligned in
            // every list it appears in, and the whitespace came from a paste.
            assertThat(UpdateContent.title("  Moulds are late  ")).isEqualTo("Moulds are late");
        }

        @Test
        @DisplayName("cannot be blank")
        void cannotBeBlank() {
            // Whitespace only, not merely empty. A title of eighty spaces satisfies
            // "not null" and is not a title.
            assertThatThrownBy(() -> UpdateContent.title("   "))
                    .isInstanceOf(UpdateContentInvalidException.class)
                    .extracting(exception -> ((UpdateContentInvalidException) exception).field())
                    .isEqualTo("title");
        }

        @Test
        @DisplayName("cannot be null")
        void cannotBeNull() {
            assertThatThrownBy(() -> UpdateContent.title(null))
                    .isInstanceOf(UpdateContentInvalidException.class);
        }

        @Test
        @DisplayName("is measured after trimming, so trailing whitespace does not use up the budget")
        void isMeasuredAfterTrimming() {
            String atTheLimit = "t".repeat(UpdateContent.MAX_TITLE_LENGTH);
            assertThat(UpdateContent.title(atTheLimit + "   ")).isEqualTo(atTheLimit);
        }

        @Test
        @DisplayName("stops one character past the limit")
        void stopsPastTheLimit() {
            assertThatThrownBy(() -> UpdateContent.title("t".repeat(UpdateContent.MAX_TITLE_LENGTH + 1)))
                    .isInstanceOf(UpdateContentInvalidException.class)
                    .hasMessageContaining(String.valueOf(UpdateContent.MAX_TITLE_LENGTH));
        }

        @Test
        @DisplayName("counts an emoji as one character, as PostgreSQL and the editor's counter do")
        void countsCodePoints() {
            // A surrogate pair is two Java chars and one character everywhere a
            // person is counting. String.length would refuse a title of sixty
            // emoji against a limit of a hundred and twenty.
            String emoji = "🚀".repeat(UpdateContent.MAX_TITLE_LENGTH);
            assertThat(UpdateContent.title(emoji)).isEqualTo(emoji);
        }
    }

    @Nested
    @DisplayName("body")
    class Bodies {

        @Test
        @DisplayName("cannot be blank")
        void cannotBeBlank() {
            // An update with nothing in it is a notification sent about nothing.
            assertThatThrownBy(() -> UpdateContent.body("\n\t "))
                    .isInstanceOf(UpdateContentInvalidException.class)
                    .extracting(exception -> ((UpdateContentInvalidException) exception).field())
                    .isEqualTo("body");
        }

        @Test
        @DisplayName("stops one character past the limit")
        void stopsPastTheLimit() {
            assertThatThrownBy(() -> UpdateContent.body("b".repeat(UpdateContent.MAX_BODY_LENGTH + 1)))
                    .isInstanceOf(UpdateContentInvalidException.class);
        }

        @Test
        @DisplayName("keeps the newlines inside it")
        void keepsInteriorWhitespace() {
            // Only the ends are trimmed. Paragraph breaks are the only structure
            // prose has, and an update stripped of them renders as a wall.
            assertThat(UpdateContent.body("First line.\n\nSecond line.")).isEqualTo("First line.\n\nSecond line.");
        }
    }
}
