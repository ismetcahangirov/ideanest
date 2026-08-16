package az.ideanest.discovery.application;

import az.ideanest.discovery.domain.RankingTerm;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every weight, as one immutable value, with a version that changes when they do.
 *
 * <h2>Why a snapshot rather than nine lookups</h2>
 *
 * <p>A score is a weighted sum, so a query that read one weight at a time could
 * legitimately build a score out of the weights from before a change and the weights
 * from after it. That is not a small window: it is per row, per page, and across the two
 * separate statements the keyset predicate and the projected score are compiled into.
 * Taking the whole set at once and passing it down makes a mid-request change
 * impossible rather than unlikely.
 *
 * <h2>The version, and what it is for</h2>
 *
 * <p>{@link #version()} is a digest of every term's weight and active flag — <em>not</em>
 * of {@code updated_at}, so re-saving a weight at the same value does not invalidate
 * anything, and not of an instance-local counter, so two application instances that
 * hold the same weights agree on it.
 *
 * <p>It exists because <strong>a weight change mid-scroll is the same failure as a
 * filter change mid-scroll, and worse</strong>. {@code DiscoveryCursor} already refuses
 * to be replayed against a query it does not belong to, because a key from one ordering
 * means nothing in another; changing {@code w4} from 0.15 to 1.5 between page one and
 * page two reorders the entire feed, so the cursor's key means nothing either. So
 * {@code PostgresSearchService} folds this version into the fingerprint it binds the
 * cursor to, and a scroll that spans a tuning change is refused with
 * {@code DISCOVERY_CURSOR_MISMATCH} and restarts — which is loud, recoverable, and what
 * the mechanism already does for every other reason the ordering can change.
 *
 * @param version see above; stable across instances, changes only when a weight or an
 *     active flag does
 * @param weights one entry per {@link RankingTerm}, in §11.2's order
 */
public record RankingWeights(String version, List<RankingWeight> weights) {

    public RankingWeights {
        weights = List.copyOf(weights);
    }

    /**
     * A snapshot of these rows, with the version derived from them.
     *
     * <p>Missing terms are tolerated rather than refused. V15 seeds all nine and the
     * vocabulary is a CHECK constraint, so a row can only be missing if somebody deleted
     * it by hand — and the useful behaviour then is a term that contributes nothing,
     * which is what {@link #isActive} answers, rather than a discovery feed that will not
     * serve at all.
     */
    public static RankingWeights of(List<RankingWeight> weights) {
        List<RankingWeight> ordered = new ArrayList<>();
        Map<RankingTerm, RankingWeight> byTerm = index(weights);
        for (RankingTerm term : RankingTerm.values()) {
            RankingWeight weight = byTerm.get(term);
            if (weight != null) {
                ordered.add(weight);
            }
        }
        return new RankingWeights(digestOf(ordered), ordered);
    }

    public Optional<RankingWeight> find(RankingTerm term) {
        return weights.stream().filter(weight -> weight.term() == term).findFirst();
    }

    /** Whether this term is in the sum. False for a term that is absent or switched off. */
    public boolean isActive(RankingTerm term) {
        return find(term).map(RankingWeight::active).orElse(false);
    }

    /** The multiplier, or zero when the term is absent or switched off. */
    public BigDecimal weightOf(RankingTerm term) {
        return find(term).map(RankingWeight::effectiveWeight).orElse(BigDecimal.ZERO);
    }

    /** Whether anything at all is being scored. See {@code RelevanceScore.of}. */
    public boolean hasActiveTerm() {
        return weights.stream().anyMatch(RankingWeight::active);
    }

    private static Map<RankingTerm, RankingWeight> index(List<RankingWeight> weights) {
        Map<RankingTerm, RankingWeight> byTerm = new EnumMap<>(RankingTerm.class);
        for (RankingWeight weight : weights) {
            byTerm.put(weight.term(), weight);
        }
        return Collections.unmodifiableMap(byTerm);
    }

    /**
     * The digest, over exactly what changes the ordering.
     *
     * <p>{@code stripTrailingZeros} because {@code 0.20} and {@code 0.2} are the same
     * weight and produce the same feed, and a version that distinguished them would
     * invalidate every open scroll whenever somebody re-typed a value — the failure of a
     * cache key that is finer than the thing it is keying.
     */
    private static String digestOf(List<RankingWeight> weights) {
        List<String> parts = new ArrayList<>();
        parts.add("v1");
        for (RankingWeight weight : weights) {
            parts.add(weight.term().wireValue()
                    + "="
                    + (weight.active() ? weight.weight().stripTrailingZeros().toPlainString() : "off"));
        }
        // The same separator DiscoveryQuery.fingerprint uses, and for the same reason:
        // it cannot occur in a wire value or a decimal, so two different sets cannot
        // digest to one string.
        String canonical = String.join(String.valueOf((char) 0x1f), parts);
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. Reaching here is not a runtime condition.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] digest = sha256.digest(canonical.getBytes(StandardCharsets.UTF_8));
        // Thirty-two bits, and this is a consistency check against the caller's own
        // previous request rather than a defence against a collision somebody searched
        // for -- the same judgement DiscoveryQuery.fingerprint makes at sixty-four.
        return HexFormat.of().formatHex(digest, 0, 4);
    }
}
