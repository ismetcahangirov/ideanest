package az.ideanest.reward.application;

import java.util.List;
import java.util.UUID;

/**
 * A reorder that does not name every tier of the campaign, exactly once.
 *
 * <p>Answered as 400 with {@code code: REWARD_ORDER_INCOMPLETE} and both halves of
 * the disagreement in {@code meta}.
 *
 * <p><strong>The full set or nothing.</strong> A partial list looks harmless and is
 * not: the tiers it omits keep the positions they had, so they interleave with the
 * ones that moved and the creator sees an order they did not ask for. Accepting it
 * would also make the request's meaning depend on what was stored when it arrived,
 * which is the same trap two browser tabs fall into. The client holds the whole
 * list — it is what the creator was dragging — so sending it is free, and being
 * refused tells the client its list is stale, which is the useful thing to know.
 */
public class RewardOrderIncompleteException extends RuntimeException {

    private final List<UUID> missing;
    private final List<UUID> unexpected;

    public RewardOrderIncompleteException(List<UUID> missing, List<UUID> unexpected) {
        super("A reorder names " + missing.size() + " tiers too few and " + unexpected.size() + " that do not belong");
        this.missing = List.copyOf(missing);
        this.unexpected = List.copyOf(unexpected);
    }

    /** Tiers of this campaign the request did not mention. */
    public List<UUID> missing() {
        return missing;
    }

    /** Identifiers the request mentioned that are not tiers of this campaign, including duplicates. */
    public List<UUID> unexpected() {
        return unexpected;
    }
}
