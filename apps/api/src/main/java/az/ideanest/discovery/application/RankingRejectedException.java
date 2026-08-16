package az.ideanest.discovery.application;

/**
 * A ranking request the platform will not carry out.
 *
 * <p>The same shape and the same reasoning as {@link CurationRejectedException}: 400
 * with the field named, because a CHECK constraint violation reaches the client as a
 * 500 and a validated field reaches them as a refusal that says which input to fix.
 * Every condition raised through this has a constraint behind it as well —
 * {@code ranking_weights_weight_is_bounded},
 * {@code ranking_weights_inert_terms_are_not_active},
 * {@code ranking_weight_changes_note_is_not_blank} — and the constraint is the one that
 * is true regardless of which code path wrote the row.
 *
 * <p>A separate type from the curation one rather than a shared "admin rejected",
 * because the two carry different codes to the client and a client that handles
 * {@code CURATION_REJECTED} should not silently start receiving ranking failures under
 * it.
 *
 * @param field the input to fix, spelled as the request spells it
 */
public class RankingRejectedException extends RuntimeException {

    private final String field;

    public RankingRejectedException(String field, String detail) {
        super(detail);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
