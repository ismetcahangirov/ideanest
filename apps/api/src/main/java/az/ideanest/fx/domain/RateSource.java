package az.ideanest.fx.domain;

/**
 * Where a rate came from — issue #327.
 *
 * <p>One value, and the enum exists anyway. {@code exchange_rates.source} is part of the
 * unique key over a publication, so the day a second source is configured its rows sit
 * beside these rather than colliding with them — and the difference between "the central
 * bank said 1.7" and "some aggregator said 1.7" is exactly the distinction §22.1 would ask
 * about.
 *
 * <p>Stored as its name rather than its ordinal, like every other enum in this schema:
 * V59's column is {@code text}, so a value inserted between two others cannot silently
 * re-label a year of rows.
 */
public enum RateSource {

    /**
     * The Central Bank of Azerbaijan.
     *
     * <p>§21.2 says "central bank rates" and §22.1 names this institution as the regulator,
     * so it is the source the specification means rather than a convenient one. Its
     * document is public, needs no key, and is published per working day.
     */
    CBAR
}
