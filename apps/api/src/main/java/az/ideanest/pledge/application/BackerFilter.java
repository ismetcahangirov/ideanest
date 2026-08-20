package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.PledgeState;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * §4.7's CD-10: which of a campaign's backers a creator is asking about.
 *
 * <p>One value used by three surfaces — the list, the export, and a saved segment — so
 * that "backers in Germany on the early-bird tier" means the same set in all three. The
 * alternative, three parameter lists that happen to agree, is the version where an
 * exported file quietly does not match the screen it was exported from.
 *
 * <h2>Empty means "any", and never "none"</h2>
 *
 * <p>An empty {@code countries} is not a filter matching no country; it is the absence of
 * a country filter. That reading is the only one that makes an unset field harmless, and
 * it is the one {@code backer_segments} stores as {@code NULL} — V31's header says why an
 * empty array is refused there rather than being allowed to mean the same thing twice.
 *
 * <h2>The state vocabulary is closed, and narrower than the enum</h2>
 *
 * <p>{@link #REPORTED} is {@link PledgeState#ACTIVE} minus {@link PledgeState#DRAFT},
 * which is {@link PublicBackers#COUNTED} — the same set the project page counts, taken
 * from the same place rather than restated. A draft is a five-minute reservation and not
 * a person who backed anything.
 *
 * <p><strong>The terminal states are deliberately not selectable.</strong> Expired,
 * cancelled, refunded, dropped and charged back are all "no longer a backer", and a
 * report that mixed them into the fulfilment list is how somebody ships a reward to a
 * refunded pledge. What a creator needs about those is CD-17's collection status, which
 * is not built; naming that gap is better than half-answering it here.
 */
public record BackerFilter(Set<PledgeState> states, Set<UUID> rewardTiers, Set<String> countries, String term) {

    /**
     * The states the backer report covers.
     *
     * <p>Derived from {@link PublicBackers#COUNTED} rather than written out, so that the
     * report and the public backer count can only ever disagree on purpose.
     */
    public static final Set<PledgeState> REPORTED = PublicBackers.COUNTED;

    /** Every backer on the campaign: no state, tier, destination or search narrowing it. */
    public static final BackerFilter ANY = new BackerFilter(Set.of(), Set.of(), Set.of(), null);

    /** The longest search text. Long enough for an email address, short enough not to be a document. */
    public static final int MAX_TERM_LENGTH = 120;

    /** As many tiers as a campaign can plausibly have, and a bound on the array either way. */
    public static final int MAX_REWARD_TIERS = 200;

    /** Every country there is, and then some. */
    public static final int MAX_COUNTRIES = 250;

    public BackerFilter {
        states = states == null ? Set.of() : Collections.unmodifiableSet(copyOf(states));
        rewardTiers = rewardTiers == null ? Set.of() : Set.copyOf(rewardTiers);
        countries = countries == null ? Set.of() : Set.copyOf(countries);
    }

    /**
     * The filter a client described, normalised and refused if it is not one.
     *
     * <p><strong>Normalisation happens once, here.</strong> Countries arrive in whatever
     * case somebody typed and are folded to upper case, blank members are dropped rather
     * than matched against, and a term that is only whitespace becomes no term at all.
     * Doing this at the repository would mean the saved segment and the live filter could
     * normalise differently; doing it in the controller would mean each of the three
     * routes had its own copy.
     *
     * @throws InvalidBackerFilterException when a state is outside {@link #REPORTED}, a
     *     country is not two letters, or a collection is longer than the report answers.
     *     A 400 in every case: the caller sent it and the caller can fix it
     */
    public static BackerFilter of(
            Set<PledgeState> states, Set<UUID> rewardTiers, Set<String> countries, String term) {

        Set<PledgeState> requestedStates = states == null ? Set.of() : states;
        for (PledgeState state : requestedStates) {
            if (!REPORTED.contains(state)) {
                throw new InvalidBackerFilterException("The backer report covers " + names(REPORTED) + ", and "
                        + state.name() + " is not one of them. A pledge in that state is not a backer of this"
                        + " campaign.");
            }
        }

        Set<UUID> requestedTiers = rewardTiers == null ? Set.of() : rewardTiers;
        if (requestedTiers.size() > MAX_REWARD_TIERS) {
            throw new InvalidBackerFilterException(
                    "A filter names at most " + MAX_REWARD_TIERS + " reward tiers, and this one names "
                            + requestedTiers.size() + ".");
        }

        Set<String> folded = new LinkedHashSet<>();
        if (countries != null) {
            for (String country : countries) {
                if (country == null || country.isBlank()) {
                    continue;
                }
                String upper = country.strip().toUpperCase(Locale.ROOT);
                if (!upper.matches("[A-Z]{2}")) {
                    throw new InvalidBackerFilterException("A destination is an ISO 3166-1 alpha-2 code, and \""
                            + country + "\" is not one.");
                }
                folded.add(upper);
            }
        }
        if (folded.size() > MAX_COUNTRIES) {
            throw new InvalidBackerFilterException("A filter names at most " + MAX_COUNTRIES
                    + " destinations, and this one names " + folded.size() + ".");
        }

        String search = term == null || term.isBlank() ? null : term.strip();
        if (search != null && search.length() > MAX_TERM_LENGTH) {
            throw new InvalidBackerFilterException(
                    "A search is at most " + MAX_TERM_LENGTH + " characters, and this one is " + search.length()
                            + ".");
        }

        return new BackerFilter(requestedStates, requestedTiers, folded, search);
    }

    /** Whether this filter narrows anything at all. */
    public boolean isAny() {
        return states.isEmpty() && rewardTiers.isEmpty() && countries.isEmpty() && term == null;
    }

    /**
     * The states to match, with the empty set expanded to {@link #REPORTED}.
     *
     * <p>The expansion happens here rather than in the SQL so that "any" and "all five"
     * are one code path in the query. See {@code BackerListRepository} for why the query
     * <em>also</em> carries the five as literals.
     */
    public Set<PledgeState> effectiveStates() {
        return states.isEmpty() ? REPORTED : states;
    }

    private static Set<PledgeState> copyOf(Set<PledgeState> states) {
        return states.isEmpty() ? EnumSet.noneOf(PledgeState.class) : EnumSet.copyOf(states);
    }

    private static String names(Set<PledgeState> states) {
        return states.stream().map(Enum::name).sorted().reduce((a, b) -> a + ", " + b).orElse("nothing");
    }
}
