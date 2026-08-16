package az.ideanest.discovery.application;

/**
 * A curation request the platform will not carry out.
 *
 * <p>400 with the field named, for the reason V6 gives about checking a title's length
 * in Java as well as in a CHECK constraint: a constraint violation reaches the client
 * as a 500 and a validated field as a refusal that says which input to fix. Every
 * condition raised through this has a database constraint behind it as well —
 * {@code collection_translations_locale_known}, {@code collections_window_is_ordered},
 * the foreign key from {@code collection_projects} to {@code projects} — and the
 * constraint is the one that is true regardless of which code path wrote the row.
 *
 * @param field the input to fix, spelled as the request body spells it
 */
public class CurationRejectedException extends RuntimeException {

    private final String field;

    public CurationRejectedException(String field, String detail) {
        super(detail);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
