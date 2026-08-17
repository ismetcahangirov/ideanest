package az.ideanest.shared.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the detail half of a log line out of values that could not have been
 * personal data.
 *
 * <p>{@link RedactingEncoder} is the guarantee; this is the other half of the same
 * idea, aimed at the call site. The allowlist here is not a list of permitted
 * field names — those are impossible to keep current — but a list of permitted
 * <strong>shapes</strong>. There is a method for an identifier, an amount, a
 * count, a flag, an instant, a state, and a country code, and there is no method
 * that takes free text. A field that would need one is a field that should not be
 * on the line.
 *
 * <pre>{@code
 * log.info("pledge confirmed {}", LogFields.create()
 *         .id("pledgeId", pledge.id())
 *         .amount("total", quote.total().amount())
 *         .state("state", pledge.state()));
 * }</pre>
 *
 * <p>Deliberately not a general-purpose formatter. It exists so that the safe way
 * to write a log line is also the shortest one.
 */
public final class LogFields {

    /** ISO 3166-1 alpha-2, the shape {@code pledges.shipping_country} holds. */
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    /** What a null renders as. Not the empty string, which reads as a bug. */
    private static final String ABSENT = "none";

    private final StringBuilder rendered = new StringBuilder(64);

    private LogFields() {
    }

    public static LogFields create() {
        return new LogFields();
    }

    /** A primary key. A UUID names a row; it does not name a person. */
    public LogFields id(String field, UUID value) {
        return append(field, value == null ? ABSENT : value.toString());
    }

    /** A state, a reason, a kind — anything the domain models as a closed set. */
    public LogFields state(String field, Enum<?> value) {
        return append(field, value == null ? ABSENT : value.name());
    }

    /** Money, as a string. Never a double — see the contributing rules. */
    public LogFields amount(String field, BigDecimal value) {
        return append(field, value == null ? ABSENT : value.toPlainString());
    }

    public LogFields count(String field, long value) {
        return append(field, Long.toString(value));
    }

    public LogFields flag(String field, boolean value) {
        return append(field, Boolean.toString(value));
    }

    public LogFields at(String field, Instant value) {
        return append(field, value == null ? ABSENT : value.toString());
    }

    /**
     * A destination country. Two letters and nothing else — a country is not
     * personal data, but a shipping address is, and the difference between them
     * is exactly this shape.
     */
    public LogFields country(String field, String value) {
        if (value != null && !COUNTRY.matcher(value).matches()) {
            // The rejected value is not quoted back. Whatever it turned out to
            // be, this method has just established that nobody checked what it
            // was, and an exception message ends up in a log like everything else.
            throw new IllegalArgumentException(
                    "'%s' was given a value that is not a two-letter ISO 3166-1 country code".formatted(field));
        }
        return append(field, value == null ? ABSENT : value);
    }

    /** {@code field=value field=value}, ready to be the tail of a message. */
    @Override
    public String toString() {
        return rendered.toString();
    }

    private LogFields append(String field, String value) {
        if (Redaction.isSensitiveFieldName(field)) {
            // Redaction would mask it in the pipeline anyway, and a field that is
            // always masked is a field somebody eventually renames to get around.
            // Failing here says so while the code is being written.
            throw new IllegalArgumentException(
                    "'%s' names personal data or a credential and may not be logged under any shape."
                            .formatted(field));
        }
        if (!rendered.isEmpty()) {
            rendered.append(' ');
        }
        rendered.append(field).append('=').append(value);
        return this;
    }
}
