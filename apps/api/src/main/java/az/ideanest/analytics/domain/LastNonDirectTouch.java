package az.ideanest.analytics.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * <strong>The attribution rule. All of it.</strong>
 *
 * <p>Among the visits recorded for one visitor on one campaign, the pledge belongs to
 * the most recent one that is not {@link ReferralChannel#DIRECT}, ignoring any whose
 * window had already closed and any recorded after the pledge itself. A pledge with no
 * such visit is attributed to {@code DIRECT} — reported as such, rather than left out
 * of the report.
 *
 * <h2>Why last, and why non-direct</h2>
 *
 * <p><strong>Last touch</strong> is the conventional default, and the argument for it
 * on a funding platform is that the alternatives need data this platform does not
 * have. First touch credits whatever introduced somebody months ago and is the right
 * answer only if you believe nothing since then mattered; a linear or time-decayed
 * split needs a model of how much each touch was worth, and a model nobody has
 * calibrated is a preference with arithmetic on top. Last touch is the one rule a
 * creator can check against their own memory of what they posted.
 *
 * <p><strong>Non-direct</strong> is where the rule earns its name. Somebody who
 * arrives from a newsletter, thinks about it overnight and then types the address in
 * has still been brought here by the newsletter. A plain last-touch rule would
 * attribute that pledge to "direct" — and since deliberation is exactly what happens
 * before a person commits money for months, a plain rule would report the considered
 * pledges as unattributable and the impulsive ones as earned. That is the shape of
 * report that makes a creator stop spending on the thing that is working.
 *
 * <h2>Order of operations, which is not interchangeable</h2>
 *
 * <p>Filter first, then take the most recent. Doing it the other way — take the most
 * recent, then check whether it qualifies — answers "nothing" whenever the newest
 * visit happens to be direct or expired, which is a different rule that agrees with
 * this one on the easy cases only. {@code ReferralAttributionRuleTests} pins both
 * orderings apart.
 *
 * <h2>The boundaries</h2>
 *
 * <p><strong>Inclusive at the near end, exclusive at the far one.</strong> A visit at
 * exactly the pledge instant counts: the click that opened checkout can share a
 * millisecond with the pledge it led to, and dropping it would lose precisely the
 * touches that convert best. A visit whose window closes at exactly the pledge
 * instant does not: a window described as thirty days that also counted the moment it
 * ran out would be thirty days and a moment, and that moment is where every
 * off-by-one in a retention rule lives.
 *
 * <p>A pure function over what it is handed. It does not know which visitor the
 * touches belong to — the caller's query decides that — so it can be tested against a
 * list rather than against a database, which is why the rule is checked at all.
 */
public final class LastNonDirectTouch {

    /**
     * Later first, and for two visits recorded in the same instant the greater
     * identifier.
     *
     * <p>The tie-break is not arbitrary. Identifiers here are UUID version 7, whose
     * leading bits are a millisecond timestamp, so the greater identifier is the row
     * written later — and a rule that had no tie-break would return whichever row the
     * query planner happened to hand over first, which is a report that changes when
     * an index is added.
     */
    private static final Comparator<ReferralTouch> MOST_RECENT_FIRST =
            Comparator.comparing(ReferralTouch::getOccurredAt).thenComparing(ReferralTouch::getId).reversed();

    private LastNonDirectTouch() {
    }

    /**
     * The visit this pledge belongs to, if any of them qualifies.
     *
     * @param touches the visits recorded for one visitor on one campaign, in any
     *     order. Ordering is applied here rather than assumed of the caller, so that
     *     the rule does not depend on a clause in a query somewhere else
     * @param pledgedAt when the pledge was confirmed — the instant the rule is applied
     *     against. Never "now": an event delivered an hour late must produce the same
     *     answer it would have produced on time, or a retry would change a report
     * @return empty when the visitor has no qualifying visit, which the caller records
     *     as {@link ReferralSource#direct()} rather than as nothing at all
     */
    public static Optional<ReferralTouch> of(Collection<ReferralTouch> touches, Instant pledgedAt) {
        return touches.stream()
                .filter(touch -> qualifies(touch, pledgedAt))
                .min(MOST_RECENT_FIRST);
    }

    /**
     * Whether one visit is evidence about a pledge made at this instant.
     *
     * <p>Three conditions, and each of them is a test in
     * {@code ReferralAttributionRuleTests}: it named a source, it had already happened,
     * and its window was still open.
     */
    private static boolean qualifies(ReferralTouch touch, Instant pledgedAt) {
        return !touch.getSource().isDirect()
                && !touch.getOccurredAt().isAfter(pledgedAt)
                && touch.getExpiresAt().isAfter(pledgedAt);
    }
}
