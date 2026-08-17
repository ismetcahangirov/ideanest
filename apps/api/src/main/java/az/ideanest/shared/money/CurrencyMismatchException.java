package az.ideanest.shared.money;

/**
 * Two amounts in two currencies, and something tried to treat them as one.
 *
 * <p><strong>Loud, not lenient.</strong> Adding 10 AZN to 10 USD has no answer, and
 * neither does asking which is larger. The alternatives are all worse than an
 * exception: coercing produces a number in neither currency, converting invents a
 * rate that §21.2 says is an approximation shown to a user and never the basis of a
 * collection, and returning the first operand hides the mistake in a total that
 * looks plausible.
 *
 * <p>An {@link IllegalArgumentException} because this is a programming error, not a
 * validation failure to report to a backer. Every place two amounts meet — a pledge
 * and its reward's price, a payout and its fee — the currencies are the campaign's
 * by construction, so a mismatch means the code combined two things that were never
 * comparable. It surfaces as a 500 on purpose: a 400 would suggest the client could
 * fix it by sending something else.
 */
public class CurrencyMismatchException extends IllegalArgumentException {

    private final String expected;
    private final String actual;

    public CurrencyMismatchException(String expected, String actual) {
        super("An amount in " + expected + " cannot be combined with one in " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    /** The currency of the amount that was operated on. */
    public String expected() {
        return expected;
    }

    /** The currency of the amount it was given. */
    public String actual() {
        return actual;
    }
}
