package az.ideanest.discovery.application;

import java.util.List;

/**
 * A filter value that is not one of the values that filter takes.
 *
 * <p>A 400 rather than an empty page. {@code status=finished} is a client bug or a
 * stale bookmark, and answering it with "no campaigns match" is indistinguishable
 * from a correct empty result — so the person looking at the screen concludes the
 * platform has nothing rather than that their request was wrong.
 *
 * <p><strong>A slug that names nothing is the deliberate exception.</strong>
 * {@code category=underwater-basketweaving} is <em>not</em> this error: category and
 * tag vocabularies are data, they change without a deployment, and a link shared
 * before a category was renamed should show an empty feed rather than an error page.
 * The distinction is between a value from a closed set the API defines — a status, a
 * sort, a band — and a value from an open set the platform's own content defines.
 *
 * @param parameter the query parameter, as a client spells it
 * @param value what was sent
 * @param allowed every value that would have been accepted, so the fix is in the
 *     response rather than in the documentation
 */
public class UnknownFilterValueException extends RuntimeException {

    private final String parameter;
    private final String value;
    private final List<String> allowed;

    public UnknownFilterValueException(String parameter, String value, List<String> allowed) {
        super("Unknown value for " + parameter + ": " + value);
        this.parameter = parameter;
        this.value = value;
        this.allowed = List.copyOf(allowed);
    }

    public String parameter() {
        return parameter;
    }

    public String value() {
        return value;
    }

    /** Empty when the value was rejected for its shape rather than against a list. */
    public List<String> allowed() {
        return allowed;
    }
}
