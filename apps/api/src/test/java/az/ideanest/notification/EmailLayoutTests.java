package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the colours in the email layout are still the design system's.
 *
 * <p>CLAUDE.md §2 says every colour comes from {@code @ideanest/design-tokens} and that a
 * hex literal in source fails the build. {@code email/layout.html} is full of hex
 * literals, knowingly, and the file says why at length: design tokens are CSS custom
 * properties, and Gmail, Outlook and Apple Mail resolve none of them. An email is inline
 * literals or it is unstyled.
 *
 * <p>What that costs is drift — the token package changes a value and the mail keeps
 * sending the old one, silently, because nothing connects the two files. This is what
 * connects them. Every hex literal in the layout must be a value the token package
 * publishes; the copy is then checkable even though it is a copy.
 *
 * <p><strong>It does not check the reverse.</strong> The layout uses a handful of the
 * palette and is not required to use all of it, so a token nothing in an email refers to
 * is not a failure.
 *
 * <p>No Spring context: this reads two files and compares strings, and starting a
 * PostgreSQL container to do it would make the suite slower for no coverage — which is
 * exactly what {@code AbstractIntegrationTest} asks a test like this not to do.
 */
class EmailLayoutTests {

    /** Three- or six-digit hex colours, which is every form the layout uses. */
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");

    /** From {@code apps/api}, which is where Gradle runs the tests. */
    private static final Path LAYOUT = Path.of("src/main/resources/email/layout.html");

    private static final Path TOKENS = Path.of("../../packages/design-tokens/src/theme.css");

    @Test
    @DisplayName("every colour in the email layout is one the design tokens publish")
    void theLayoutCopiesTokensRatherThanInventingColours() {
        Set<String> inLayout = coloursIn(read(LAYOUT));
        Set<String> inTokens = coloursIn(read(TOKENS));

        assertThat(inLayout)
                .as("the layout uses colours at all; a match of nothing would pass vacuously")
                .isNotEmpty();
        assertThat(inTokens).as("the token package was found and parsed").isNotEmpty();

        assertThat(inLayout)
                .withFailMessage(
                        """
                        %s uses colours that packages/design-tokens does not publish: %s

                        Either the token changed and the layout was not updated -- which is the
                        drift this test exists to catch -- or a colour was invented here, which
                        CLAUDE.md §2 does not allow. Neither is fixed by editing this test.""",
                        LAYOUT,
                        difference(inLayout, inTokens))
                .isSubsetOf(inTokens);
    }

    /**
     * The two rules that are about meaning rather than about values.
     *
     * <p>Lime is a surface with near-black text on it, never text on a light one — it
     * measures 1.3:1 and is unreadable — and it means urgent rather than successful.
     * Checked as "the lime that appears is a background", which is the form a violation
     * would take in a table-based email: a {@code color:} declaration carrying it.
     */
    @Test
    @DisplayName("lime is a surface in the email, never text")
    void limeIsNeverText() {
        String layout = read(LAYOUT).toLowerCase(Locale.ROOT);

        for (String lime : coloursIn(read(TOKENS)).stream()
                .filter(colour -> LIMES.contains(colour))
                .toList()) {

            // The lookbehind is load-bearing: a plain `contains("color:" + lime)` also
            // matches inside `background-color:`, which is the one place lime is
            // supposed to appear. The first version of this test failed on the button
            // for exactly that reason.
            assertThat(Pattern.compile("(?<![-a-z])color:\\s*" + Pattern.quote(lime))
                            .matcher(layout)
                            .find())
                    .withFailMessage(
                            "%s sets color:%s. Lime is a surface with near-black text on it, or it "
                                    + "is nothing -- lime text measures 1.3:1. See docs/ui-kit.md.",
                            LAYOUT, lime)
                    .isFalse();
        }
    }

    /** The brand ramp, by value. Named here so the test above does not depend on ordering. */
    private static final Set<String> LIMES =
            Set.of("#dcfb7a", "#d2f95c", "#c6f432", "#b0de1e", "#94bc15");

    private static Set<String> coloursIn(String source) {
        Set<String> colours = new LinkedHashSet<>();
        Matcher matcher = HEX.matcher(source);
        while (matcher.find()) {
            colours.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return colours;
    }

    private static Set<String> difference(Set<String> from, Set<String> without) {
        Set<String> difference = new LinkedHashSet<>(from);
        difference.removeAll(without);
        return difference;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            // Not a soft failure. A test that quietly passed when it could not find the
            // token package would be one that stopped checking anything the first time
            // somebody moved a directory.
            throw new UncheckedIOException(
                    "Could not read " + path.toAbsolutePath() + "; tests run from apps/api", unreadable);
        }
    }
}
