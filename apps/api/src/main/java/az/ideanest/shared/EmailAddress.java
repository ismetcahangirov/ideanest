package az.ideanest.shared;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An email address, normalised.
 *
 * <p>Trimmed and lowercased on construction, so there is one representation of
 * one address everywhere in the service: in the unique index, in a lookup, in a
 * password reset, and in the audit trail. The database column is {@code citext}
 * as well, but that only protects comparisons made <em>in</em> the database —
 * a JDBC parameter is bound as {@code varchar}, and PostgreSQL then compares it
 * as text, so a lookup for {@code Person@Example.com} silently finds nothing.
 * Normalising here is what makes the two agree.
 *
 * <p>Lives in {@code shared} rather than in {@code user} because auth,
 * notification, and the pledge manager all handle addresses, and none of them
 * should be reaching into another module's domain to name one.
 *
 * <p>The local part is <strong>not</strong> case-folded by the standard — RFC
 * 5321 leaves it to the receiving server. In practice every mail provider worth
 * accepting registrations from treats it case-insensitively, and the failure
 * mode of not folding is two accounts for one person, which is worse than the
 * failure mode of folding.
 */
public record EmailAddress(String value) {

    /**
     * Deliberately loose. The only reliable test of an address is sending mail
     * to it, which registration does; a stricter pattern rejects valid
     * addresses and catches nothing a verification email would not.
     */
    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final int MAX_LENGTH = 254;

    public EmailAddress {
        Objects.requireNonNull(value, "An email address is required");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            // The SMTP path limit. Anything longer cannot be delivered to.
            throw new IllegalArgumentException("An email address may not exceed " + MAX_LENGTH + " characters");
        }
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("Not an email address");
        }
    }

    public static EmailAddress of(String raw) {
        return new EmailAddress(raw);
    }

    /**
     * Masked. This type ends up in log lines and exception messages, and §17.4
     * keeps addresses out of both. Code that genuinely needs the address —
     * sending to it, storing it — asks for {@link #value()} and says so.
     */
    @Override
    public String toString() {
        int at = value.indexOf('@');
        String local = value.substring(0, at);
        String domain = value.substring(at);
        String visible = local.substring(0, Math.min(1, local.length()));
        return visible + "***" + domain;
    }
}
