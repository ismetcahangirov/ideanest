package az.ideanest.risk.domain;

/**
 * What the platform does about a score — issue #108.
 *
 * <p>The thresholds are configuration ({@code RiskProperties}), because the only honest
 * way to set them is to watch a month of real pledges and move them, and a threshold in
 * source is a threshold nobody moves.
 */
public enum RiskDecision {

    /** Nothing worth a person's time. The overwhelming majority. */
    ALLOW,

    /**
     * A person should look before the money is collected.
     *
     * <p>There is time for that, and it is why an advisory signal is useful on this
     * platform rather than merely tidy: a confirmed pledge is not charged until the
     * campaign reaches its goal at its deadline (§9.5), so the gap between "flagged" and
     * "collected" is days or weeks rather than milliseconds.
     */
    REVIEW,

    /**
     * Reserved, and nothing produces it.
     *
     * <p>Blocking a pledge on an automated score is a product decision with a refund
     * policy behind it and a false-positive rate somebody has agreed to. Neither exists.
     * The constant is here so that the column's check constraint does not have to change
     * on the day one does, and so that reading this enum tells you the decision has not
     * been taken rather than that it was never considered.
     */
    BLOCK
}
