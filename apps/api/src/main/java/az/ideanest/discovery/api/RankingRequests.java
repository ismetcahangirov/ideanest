package az.ideanest.discovery.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * The bodies {@link AdminRankingController} accepts.
 *
 * <p>Bean validation covers presence and blankness; the range of a weight and the rule
 * that an inert term cannot be switched on are checked in {@code RankingService}, beside
 * the reasoning, and are constraints in V15 as well. Two places rather than three: the
 * annotation says the field is there, the service says what it may be, and the database
 * says what is true whichever code path wrote the row.
 */
final class RankingRequests {

    private RankingRequests() {
    }

    /**
     * One term's new setting.
     *
     * @param weight <strong>a JSON number, deliberately, unlike every amount on this
     *     platform.</strong> Money crosses the API as a string because a client that
     *     parsed it as a double would put a pledge on the wrong side of a boundary
     *     (CLAUDE.md §3). A ranking weight is not money — nobody is paid it and no
     *     balance is derived from it — and it is a value a person types into an admin
     *     field with two decimal places. Jackson binds it to {@code BigDecimal} from the
     *     literal digits rather than through a {@code double}, so nothing rounds on the
     *     way in and the exactness the keyset cursor needs is preserved
     * @param active whether the term is in the sum. Required rather than optional,
     *     because a request that changed a weight and left the flag implicit would be
     *     ambiguous about the one thing it is most important to be sure of
     * @param note why. Required, stored, and never editable afterwards
     */
    record SetWeight(
            @NotNull BigDecimal weight,
            @NotNull Boolean active,
            @NotBlank String note) {
    }
}
