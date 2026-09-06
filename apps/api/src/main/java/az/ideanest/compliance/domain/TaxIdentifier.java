package az.ideanest.compliance.domain;

import java.util.regex.Pattern;

/**
 * The shape of a VÖEN, and deliberately nothing beyond it — issue #430.
 *
 * <h2>What this checks</h2>
 *
 * <p>Ten digits. That is the form of an Azerbaijani taxpayer identification number, and it is
 * the entire contract of this class.
 *
 * <h2>What this does not check, and why the omission is the design</h2>
 *
 * <p>#430 is explicit, and it is worth keeping the reasoning next to the code rather than in
 * an issue nobody will reopen:
 *
 * <blockquote>
 * The platform is not a company registry and must not pretend to be one. [...] Writing a
 * "validator" that appears to confirm existence would be worse than none: it would produce a
 * green tick that means nothing, in front of the exact field where a green tick is relied
 * upon.
 * </blockquote>
 *
 * <p>So this does not ask whether the number names a real company, whether that company
 * exists today, or whether the person typing it may act for it. Those are answered by a human
 * reading a registration extract — V58's {@code COMPANY_REGISTRATION} document, in #431's
 * review queue — and by nothing else.
 *
 * <p><strong>There is no checksum here, and that is a decision rather than an oversight.</strong>
 * #430 names a checksum as the kind of thing that belongs in a constraint, and it would, if
 * the algorithm were one this repository could state with confidence. It is not. A checksum
 * implemented from a guess rejects valid numbers, and a field that refuses a company's real
 * VÖEN is worse than one that accepts a typo: the typo is caught by the human who reads the
 * extract, and the false refusal is caught by nobody, because the creator concludes the
 * platform is broken and leaves. If the algorithm is confirmed — the same conversation as
 * #422's and #423's — it is added here, with a test naming the source it came from.
 */
public final class TaxIdentifier {

    /** Ten digits, and no separators: a stored identifier that varies in punctuation cannot be compared. */
    private static final Pattern VOEN = Pattern.compile("^[0-9]{10}$");

    private TaxIdentifier() {
    }

    /**
     * Normalise what somebody typed into what is stored, or refuse it.
     *
     * <p>Spaces and dashes are stripped before the shape is checked, because a person copying
     * a number off a certificate copies its punctuation too, and a refusal over a space is the
     * false refusal this class exists to avoid.
     *
     * @throws MalformedTaxIdentifierException when what is left is not ten digits
     */
    public static String normalise(String typed) {
        if (typed == null) {
            throw new MalformedTaxIdentifierException(null);
        }
        String stripped = typed.replaceAll("[\\s-]", "");
        if (!VOEN.matcher(stripped).matches()) {
            throw new MalformedTaxIdentifierException(typed);
        }
        return stripped;
    }

    /** Whether a stored value is the shape this class stores. Used by tests and by nothing else. */
    public static boolean isWellShaped(String value) {
        return value != null && VOEN.matcher(value).matches();
    }
}
