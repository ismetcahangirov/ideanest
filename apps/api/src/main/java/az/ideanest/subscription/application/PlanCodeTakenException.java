package az.ideanest.subscription.application;

/**
 * Two plans cannot share a code.
 *
 * <p>Refused with a sentence naming the code, rather than by V62's unique index, because
 * an operator adding a plan is usually re-adding one they unlisted -- and "GROWTH already
 * exists" sends them to unlist it back on, whereas a constraint violation sends them to
 * ask somebody.
 */
public class PlanCodeTakenException extends RuntimeException {

    private final String code;

    public PlanCodeTakenException(String code) {
        super("A plan already has the code " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
