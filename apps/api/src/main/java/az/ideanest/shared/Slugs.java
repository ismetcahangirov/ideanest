package az.ideanest.shared;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a display name into the lowercase, hyphenated form that appears in a
 * URL.
 *
 * <p>Azerbaijani is the primary language, so the folding is written for it
 * rather than inherited from a library that assumes Latin-1. {@code ə} has no
 * decomposition — Unicode normalisation leaves it untouched — so a name like
 * "Səbinə" would otherwise slug to something containing a character that half
 * the systems downstream will percent-encode and the other half will mangle.
 * The same applies to {@code ı}, whose uppercase is {@code I} and whose
 * lowercase in a Turkish locale is not what a default {@code toLowerCase} would
 * produce; every case conversion here is explicitly {@link Locale#ROOT}.
 *
 * <p>The folding matches {@code docs/architecture.md} §11.3, which asks the
 * search index to fold the same characters. A slug and a search term that
 * disagree about what "Sabina" folds to would be two answers to one question.
 */
public final class Slugs {

    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");

    /**
     * The Azerbaijani letters Unicode normalisation will not decompose. The
     * rest — é, ü as u-with-diaeresis, and so on — are handled by NFKD below.
     */
    private static final String[][] FOLDINGS = {
        {"ə", "e"}, {"Ə", "e"},
        {"ı", "i"}, {"İ", "i"},
        {"ğ", "g"}, {"Ğ", "g"},
        {"ş", "s"}, {"Ş", "s"},
        {"ç", "c"}, {"Ç", "c"},
        {"ö", "o"}, {"Ö", "o"},
        {"ü", "u"}, {"Ü", "u"},
    };

    private Slugs() {
    }

    /**
     * The slug for a name, or an empty string if nothing survives folding —
     * a name written entirely in a script this does not transliterate, for
     * instance. Callers decide what to do with that; silently inventing a slug
     * here would hide the case.
     */
    public static String slugify(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String folded = raw;
        for (String[] folding : FOLDINGS) {
            folded = folded.replace(folding[0], folding[1]);
        }
        // Decomposes the accented characters that do decompose — é to e plus a
        // combining acute — so that stripping the marks leaves the letter.
        folded = Normalizer.normalize(folded, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);

        String slug = NON_SLUG.matcher(folded).replaceAll("-");
        return EDGE_HYPHENS.matcher(slug).replaceAll("");
    }
}
