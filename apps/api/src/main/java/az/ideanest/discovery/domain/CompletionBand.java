package az.ideanest.discovery.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How far along a campaign is, in the five bands of §4.3.
 *
 * <p><strong>Every band is closed below and open above.</strong> {@code [lower,
 * upper)}. §4.3 writes them as "under 25%, 25–50%, 50–75%, 75–100%, over 100%",
 * which leaves three of the four boundary values in two bands at once and the
 * fourth in none; a campaign at exactly 50% would be counted twice in a facet and
 * returned twice by a filter that selected both. Half-open intervals are the only
 * reading under which the five bands partition the line.
 *
 * <p><strong>Exactly 100% is {@link #OVER_100}.</strong> §4.3's label says "over",
 * and the rule above puts the boundary in the upper band — but the two agree on
 * what matters here anyway, because the question a backer is asking is "did it
 * make it", and a campaign standing exactly at its goal did. Putting 100% in
 * {@code 75–100} would file a funded campaign under "nearly".
 *
 * <p><strong>Completion is a ratio of money and is compared as one.</strong> The
 * predicate is {@code pledged × 100 ≥ goal × lower} rather than
 * {@code pledged / goal ≥ lower / 100}: {@code numeric} multiplication is exact and
 * division is not, and {@code goal_amount > 0} is a check constraint on the table,
 * so multiplying through never changes the direction of the comparison. A campaign
 * with no goal — every {@code PRELAUNCH} row — has no completion and matches no
 * band.
 */
public enum CompletionBand {

    /** {@code [0, 25)}. Includes a campaign that has raised nothing at all. */
    UNDER_25("under_25", BigDecimal.ZERO, new BigDecimal("25")),

    /** {@code [25, 50)}. Exactly 25% is here, not in {@link #UNDER_25}. */
    FROM_25_TO_50("25_to_50", new BigDecimal("25"), new BigDecimal("50")),

    /** {@code [50, 75)}. */
    FROM_50_TO_75("50_to_75", new BigDecimal("50"), new BigDecimal("75")),

    /** {@code [75, 100)}. Exactly 100% is <em>not</em> here. */
    FROM_75_TO_100("75_to_100", new BigDecimal("75"), new BigDecimal("100")),

    /** {@code [100, ∞)}. Exactly 100% is here. No upper bound. */
    OVER_100("over_100", new BigDecimal("100"), null);

    private static final Map<String, CompletionBand> BY_WIRE_VALUE = byWireValue();

    private final String wireValue;
    private final BigDecimal lowerInclusive;
    private final BigDecimal upperExclusive;

    CompletionBand(String wireValue, BigDecimal lowerInclusive, BigDecimal upperExclusive) {
        this.wireValue = wireValue;
        this.lowerInclusive = lowerInclusive;
        this.upperExclusive = upperExclusive;
    }

    public String wireValue() {
        return wireValue;
    }

    /** Percent, inclusive. */
    public BigDecimal lowerInclusive() {
        return lowerInclusive;
    }

    /** Percent, exclusive. Null on {@link #OVER_100}, which has no ceiling. */
    public BigDecimal upperExclusive() {
        return upperExclusive;
    }

    public static Optional<CompletionBand> fromWireValue(String value) {
        return Optional.ofNullable(value).map(BY_WIRE_VALUE::get);
    }

    public static List<String> wireValues() {
        return List.copyOf(BY_WIRE_VALUE.keySet());
    }

    private static Map<String, CompletionBand> byWireValue() {
        Map<String, CompletionBand> map = new LinkedHashMap<>();
        for (CompletionBand band : values()) {
            map.put(band.wireValue, band);
        }
        return Collections.unmodifiableMap(map);
    }
}
