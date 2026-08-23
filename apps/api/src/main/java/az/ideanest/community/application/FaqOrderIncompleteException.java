package az.ideanest.community.application;

import java.util.List;
import java.util.UUID;

/**
 * A reorder that does not name every FAQ entry of the campaign, exactly once.
 *
 * <p>Answered as 400 with {@code code: FAQ_ORDER_INCOMPLETE} and both halves of the
 * disagreement in {@code meta} — the same shape {@code RewardOrderIncompleteException}
 * uses, because it is the same request against a different list and a client that can
 * report one should not have to learn a second body to report the other.
 *
 * <p><strong>The full set or nothing.</strong> A partial list looks harmless and is not:
 * the entries it omits keep the positions they had, so they interleave with the ones that
 * moved and the creator sees an order they did not ask for. Accepting it would also make
 * the request's meaning depend on what was stored when it arrived, which is the trap two
 * browser tabs fall into. The client holds the whole list — it is what the creator was
 * dragging — so sending it is free, and being refused tells the client its list is stale,
 * which is the useful thing to know.
 */
public class FaqOrderIncompleteException extends RuntimeException {

    private final List<UUID> missing;
    private final List<UUID> unexpected;

    public FaqOrderIncompleteException(List<UUID> missing, List<UUID> unexpected) {
        super("A reorder names " + missing.size() + " entries too few and " + unexpected.size()
                + " that do not belong");
        this.missing = List.copyOf(missing);
        this.unexpected = List.copyOf(unexpected);
    }

    /** Entries of this campaign the request did not mention. */
    public List<UUID> missing() {
        return missing;
    }

    /** Identifiers the request mentioned that are not entries of this campaign, repeats included. */
    public List<UUID> unexpected() {
        return unexpected;
    }
}
