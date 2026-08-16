package az.ideanest.discovery.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The money bands of §4.3, used by both the goal filter and the amount-raised
 * filter.
 *
 * <p>§4.3 asks for "bands plus a custom range" and does not say where the bands
 * are, so these are a decision made here. They are chosen against what an
 * Azerbaijani crowdfunding campaign actually asks for rather than against a
 * translated dollar scale: a few hundred manat for a photo book, a few thousand for
 * a short film or a first production run, tens of thousands for hardware, and a
 * long tail above that.
 *
 * <p><strong>Closed below, open above,</strong> exactly as {@link CompletionBand},
 * and for the same reason: overlapping bands double-count in a facet. A campaign
 * whose goal is exactly 5,000 is in {@link #FROM_5000_TO_20000}, not in the band
 * below it.
 *
 * <p><strong>One currency.</strong> The bounds are plain amounts and are compared
 * against {@code goal_amount} and {@code pledged_amount} directly, which is only
 * correct while every campaign is denominated in the same currency —
 * {@code projects.currency} defaults to AZN and §21.2 has not introduced a second
 * one. When it does, this comparison needs an exchange table and a reference
 * currency, and that is a decision for the issue that introduces the second
 * currency rather than a conversion invented here.
 *
 * <p>{@code BigDecimal} throughout, never {@code double}. These are money.
 */
public enum AmountBand {

    /** {@code [0, 1000)}. */
    UNDER_1000("under_1000", BigDecimal.ZERO, new BigDecimal("1000.00")),

    /** {@code [1000, 5000)}. */
    FROM_1000_TO_5000("1000_to_5000", new BigDecimal("1000.00"), new BigDecimal("5000.00")),

    /** {@code [5000, 20000)}. */
    FROM_5000_TO_20000("5000_to_20000", new BigDecimal("5000.00"), new BigDecimal("20000.00")),

    /** {@code [20000, 50000)}. */
    FROM_20000_TO_50000("20000_to_50000", new BigDecimal("20000.00"), new BigDecimal("50000.00")),

    /** {@code [50000, ∞)}. */
    OVER_50000("over_50000", new BigDecimal("50000.00"), null);

    private static final Map<String, AmountBand> BY_WIRE_VALUE = byWireValue();

    private final String wireValue;
    private final BigDecimal lowerInclusive;
    private final BigDecimal upperExclusive;

    AmountBand(String wireValue, BigDecimal lowerInclusive, BigDecimal upperExclusive) {
        this.wireValue = wireValue;
        this.lowerInclusive = lowerInclusive;
        this.upperExclusive = upperExclusive;
    }

    public String wireValue() {
        return wireValue;
    }

    public BigDecimal lowerInclusive() {
        return lowerInclusive;
    }

    /** Null on {@link #OVER_50000}. */
    public BigDecimal upperExclusive() {
        return upperExclusive;
    }

    public static Optional<AmountBand> fromWireValue(String value) {
        return Optional.ofNullable(value).map(BY_WIRE_VALUE::get);
    }

    public static List<String> wireValues() {
        return List.copyOf(BY_WIRE_VALUE.keySet());
    }

    private static Map<String, AmountBand> byWireValue() {
        Map<String, AmountBand> map = new LinkedHashMap<>();
        for (AmountBand band : values()) {
            map.put(band.wireValue, band);
        }
        return Collections.unmodifiableMap(map);
    }
}
