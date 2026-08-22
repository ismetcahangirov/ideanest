package az.ideanest.pledgemanager.application;

/**
 * More questions than the platform will ask on one survey.
 *
 * <p>The bound is {@code ideanest.pledge-manager.surveys.max-questions}, and it is a
 * product rule rather than a technical one: every question is asked of every backer, so
 * a survey is a form several thousand people have to finish, and what the creator
 * actually needs is the response rate.
 *
 * <p>422, and it names both numbers — a limit a client cannot see is one it cannot warn
 * about before the creator has typed thirty-one questions.
 */
public class TooManyQuestionsException extends RuntimeException {

    private final int limit;
    private final int requested;

    public TooManyQuestionsException(int limit, int requested) {
        super("A survey holds at most " + limit + " questions, not " + requested);
        this.limit = limit;
        this.requested = requested;
    }

    public int limit() {
        return limit;
    }

    public int requested() {
        return requested;
    }
}
