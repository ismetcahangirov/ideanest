package az.ideanest.community.application;

/**
 * The fifty-first entry on one campaign's FAQ tab.
 *
 * <p>Answered as 409 with {@code code: TOO_MANY_FAQS} and the limit in {@code meta}. A
 * conflict rather than a bad request: the entry being written is perfectly valid, and
 * what refuses it is how many already exist.
 *
 * <p><strong>This cap is what makes the unpaged read honest.</strong>
 * {@code GET /v1/projects/{id}/faqs} returns the whole list, because §10.2 gives it no
 * cursor and an FAQ list is tens of rows — and "tens of rows" is a claim rather than a
 * fact unless something enforces it. Fifty entries of at most 200 and 4,000 characters
 * bound that response at roughly two hundred kilobytes in a worst case nobody will write.
 * If a campaign ever genuinely needs more, the answer is a cursor on that endpoint rather
 * than a larger number here.
 *
 * <p>Not a check constraint, unlike almost everything else about this table, for
 * {@code TooManyRewardsException}'s reason: a count across rows cannot be expressed as
 * one, and a trigger or a denormalised counter would cost more than the rule is worth. It
 * is checked in one place, on the one path that creates an entry.
 */
public class TooManyFaqsException extends RuntimeException {

    private final int limit;

    public TooManyFaqsException(int limit) {
        super("A campaign has at most " + limit + " FAQ entries");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
