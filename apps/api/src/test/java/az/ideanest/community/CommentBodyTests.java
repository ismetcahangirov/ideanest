package az.ideanest.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.community.domain.CommentBody;
import az.ideanest.community.domain.CommentContentInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a comment may say.
 *
 * <p>No database and no HTTP, for {@code UpdateContentTests}' reason: {@link CommentBody}
 * is a pure type, and asserting on a table of rules through a request that also had to
 * authenticate, authorise and open a transaction would make each of these fifty times
 * slower and its failures fifty times less specific.
 */
class CommentBodyTests {

    @Test
    @DisplayName("is stored trimmed")
    void isTrimmed() {
        assertThat(CommentBody.of("  Will this ship to Baku?  ")).isEqualTo("Will this ship to Baku?");
    }

    @Test
    @DisplayName("cannot be blank")
    void cannotBeBlank() {
        // Whitespace only, not merely empty. Eighty spaces satisfies "not null" and
        // is a row in a public thread saying nothing.
        assertThatThrownBy(() -> CommentBody.of("   "))
                .isInstanceOf(CommentContentInvalidException.class)
                .extracting(exception -> ((CommentContentInvalidException) exception).field())
                .isEqualTo("body");
    }

    @Test
    @DisplayName("refuses whitespace that is not a space")
    void refusesOtherWhitespace() {
        // The one V25's `!~ '^\s*$'` exists for: PostgreSQL's one-argument btrim
        // removes spaces and nothing else, so a body of newlines would pass a
        // btrim-based check. Java's isBlank does not, and the two have to agree.
        assertThatThrownBy(() -> CommentBody.of("\n\t "))
                .isInstanceOf(CommentContentInvalidException.class);
    }

    @Test
    @DisplayName("cannot be null")
    void cannotBeNull() {
        assertThatThrownBy(() -> CommentBody.of(null)).isInstanceOf(CommentContentInvalidException.class);
    }

    @Test
    @DisplayName("is measured after trimming, so trailing whitespace does not use up the budget")
    void isMeasuredAfterTrimming() {
        String atTheLimit = "c".repeat(CommentBody.MAX_LENGTH);
        assertThat(CommentBody.of(atTheLimit + "   ")).isEqualTo(atTheLimit);
    }

    @Test
    @DisplayName("stops one character past the limit")
    void stopsPastTheLimit() {
        assertThatCode(() -> CommentBody.of("c".repeat(CommentBody.MAX_LENGTH))).doesNotThrowAnyException();
        assertThatThrownBy(() -> CommentBody.of("c".repeat(CommentBody.MAX_LENGTH + 1)))
                .isInstanceOf(CommentContentInvalidException.class);
    }

    @Test
    @DisplayName("counts an emoji as one character, as PostgreSQL's char_length does")
    void countsCodePoints() {
        // Two chars in Java, one code point, one character to char_length. Measuring
        // with String.length would refuse a comment the constraint would have stored,
        // and the counter on screen would disagree with both.
        String emoji = "🚀";
        assertThatCode(() -> CommentBody.of(emoji.repeat(CommentBody.MAX_LENGTH))).doesNotThrowAnyException();
    }
}
