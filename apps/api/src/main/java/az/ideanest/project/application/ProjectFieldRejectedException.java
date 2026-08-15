package az.ideanest.project.application;

/**
 * One field of an edit was refused.
 *
 * <p>Answered as 400 with {@code code: PROJECT_FIELD_INVALID} and the field name
 * in {@code meta}, so the editor can put the message beside the input that caused
 * it rather than in a banner at the top of a long form.
 *
 * <p>Every rule enforced through this exception is also a check constraint on
 * {@code projects}. That is not duplication with a shrug: the constraint is what
 * holds against a support query and a bulk import, and this is what turns the
 * same rule into a 400 the client can act on instead of a constraint violation
 * and a 500.
 */
public class ProjectFieldRejectedException extends RuntimeException {

    private final String field;

    /**
     * @param field the JSON field name, as the client sent it — {@code durationDays},
     *     not {@code duration_days}. A client cannot highlight an input it does not
     *     have a name for
     * @param message written for the creator, so it says what is allowed rather
     *     than which constraint was violated
     */
    public ProjectFieldRejectedException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
