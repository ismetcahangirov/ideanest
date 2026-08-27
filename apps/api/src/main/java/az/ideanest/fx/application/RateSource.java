package az.ideanest.fx.application;

import java.util.List;

/**
 * Somewhere rates come from — issue #327.
 *
 * <p>A port, so that the refresh does not know whether it is talking to a central bank, a
 * mirror or a fixture. There is one implementation and the seam is not speculative: the
 * suite needs a source it can make fail, make stale, and make return a rouble quoted per
 * hundred, and none of those are things to ask a public website for on a schedule.
 *
 * <h2>It throws rather than returning an empty list</h2>
 *
 * "The source answered and published nothing" and "the source could not be reached" are
 * different facts and the caller acts on them differently: the first is a day with no
 * publication and the second is an outage worth logging. An implementation that flattened
 * them into an empty list would make the refresh quietly accept an unreachable source for
 * ever.
 */
public interface RateSource {

    /** Which source this is, for the row it produces. */
    az.ideanest.fx.domain.RateSource name();

    /**
     * Everything the source publishes today, priced in its own base currency.
     *
     * @return one entry per currency, normalised to a nominal of one. Empty when the source
     *     answered and had nothing to say
     * @throws RateSourceUnavailableException when it could not be reached, answered with a
     *     failure, or returned something that is not the document it promised
     */
    List<PublishedRate> fetch();
}
