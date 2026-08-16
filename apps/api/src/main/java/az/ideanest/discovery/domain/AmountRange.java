package az.ideanest.discovery.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One money dimension of the filter — the goal, or the amount raised — as §4.3
 * asks for it: bands, a custom range, or both.
 *
 * <p><strong>Bands are OR'd with each other and AND'd with the custom range.</strong>
 * Selecting two bands means "either", because the bands partition the line and a
 * campaign is in exactly one of them; requiring both would return nothing. A custom
 * range alongside them narrows further, because a caller who typed bounds meant
 * them. In practice a client sends one or the other, and the rule exists so that
 * the answer to sending both is stated rather than discovered.
 *
 * <p><strong>The custom range is inclusive at both ends.</strong> Unlike the bands,
 * which have to be half-open to partition. A person typing "from 1000 to 5000" into
 * two boxes means 1000 and 5000 to be included, and the case for consistency with
 * the bands is much weaker than the case for the filter meaning what it says.
 *
 * @param bands empty for "no band selected"
 * @param minInclusive null for no lower bound
 * @param maxInclusive null for no upper bound
 */
public record AmountRange(Set<AmountBand> bands, BigDecimal minInclusive, BigDecimal maxInclusive) {

    /** No constraint on this dimension at all. */
    public static final AmountRange ANY = new AmountRange(Set.of(), null, null);

    public AmountRange {
        bands = bands == null || bands.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(bands));
        if (minInclusive != null && maxInclusive != null && minInclusive.compareTo(maxInclusive) > 0) {
            // A range that cannot contain anything is a client bug, and answering
            // it with an empty page would look like "no campaigns match" rather
            // than "you asked for amounts above 5000 and below 1000".
            throw new IllegalArgumentException("A minimum amount cannot exceed the maximum");
        }
        if (minInclusive != null && minInclusive.signum() < 0) {
            throw new IllegalArgumentException("An amount cannot be negative");
        }
        if (maxInclusive != null && maxInclusive.signum() < 0) {
            throw new IllegalArgumentException("An amount cannot be negative");
        }
    }

    public boolean isAny() {
        return bands.isEmpty() && minInclusive == null && maxInclusive == null;
    }
}
