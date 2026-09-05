package az.ideanest.signature.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Who the certificate says signed — V67's three subject columns, issue #428.
 *
 * <p><strong>The subject line and the two fields parsed out of it, together.</strong> Keeping
 * the verbatim line beside the parse is what lets a later disagreement about the parse be
 * settled without going back to the certificate — which the platform deliberately does not
 * keep. An adapter that returned only {@code name} and {@code fin} would be asking to be
 * believed about a transformation nobody can check.
 *
 * <p><strong>What is not here is the certificate.</strong> #428 is explicit: no copy of the
 * citizen's certificate material, and no identity document. That is V58's table, with V58's
 * encryption and V58's retention sweep, and this must not become a second uncontrolled place
 * where a person's identity sits.
 *
 * @param distinguishedName the subject line exactly as the provider stated it
 * @param name the citizen's name, as the state issued it. §17.4 personal data; V67's header
 *     argues why it is kept and #423 governs for how long
 * @param fin Azerbaijan's seven-character personal identification number. Shape-checked here
 *     and again by V67, because a value that is not a FIN is one #429 would one day match an
 *     account against
 */
public record CertificateSubject(String distinguishedName, String name, String fin) {

    /** V67's {@code signatures_subject_fin_shape}, restated so a bad parse fails in the adapter. */
    private static final Pattern FIN = Pattern.compile("^[0-9A-Z]{7}$");

    public CertificateSubject {
        Objects.requireNonNull(distinguishedName, "distinguishedName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fin, "fin");
        if (distinguishedName.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("A certificate subject carries a subject line and a name");
        }
        if (!FIN.matcher(fin).matches()) {
            // Refused here rather than at the insert, so that the adapter is what fails when a
            // provider changes its subject format -- which is where somebody can read the raw
            // line and fix the parse. A constraint violation three layers up names the column
            // and not the cause.
            throw new IllegalArgumentException("%s is not a FIN".formatted(fin));
        }
    }
}
