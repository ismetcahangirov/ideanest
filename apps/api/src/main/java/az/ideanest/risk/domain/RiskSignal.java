package az.ideanest.risk.domain;

/**
 * The signals §17.2 names, and one it does not — issue #108.
 *
 * <p>The set is closed and the weights live on {@code RiskProperties} rather than here: a
 * weight is a number somebody tunes after watching a month of chargebacks, and a weight in
 * an enum is a deployment.
 */
public enum RiskSignal {

    /**
     * How many pledges this account has made in the velocity window.
     *
     * <p>The signal §17.2 leads with, and the one that catches card testing: a stolen card
     * is tried against small pledges in quick succession, because the tester is looking
     * for an authorisation rather than for a reward.
     */
    PLEDGE_VELOCITY_ACCOUNT,

    /**
     * How many pledges have come from this source address in the same window, across every
     * account.
     *
     * <p>Separate from the one above because the pattern it catches is the one that defeats
     * it: ten accounts making one pledge each is invisible per account and obvious per
     * address. Both are needed and neither subsumes the other.
     */
    PLEDGE_VELOCITY_ADDRESS,

    /**
     * How old the account is.
     *
     * <p>Not a fraud signal on its own — everybody's account is new once, and a platform
     * that treated newness as guilt would be flagging its own growth. It is a multiplier
     * on the others, which is why it carries the smallest weight of the four and why the
     * thresholds are hours rather than days.
     */
    NEW_ACCOUNT,

    /**
     * A source address this account has never been seen from before.
     *
     * <p>Not in §17.2's list, and it is here because it is the half of "mismatched
     * geography" this platform can actually answer. §4.10 already treats an unfamiliar
     * address as worth telling somebody about ({@code NEW_DEVICE_SIGN_IN}); a pledge from
     * one is the same fact at the moment money is committed.
     */
    UNFAMILIAR_ADDRESS,

    /**
     * The country of the source address against the country the reward is going to.
     *
     * <p>§17.2's "geography mismatch", and <strong>the one signal this platform cannot
     * evaluate today</strong>. It needs an IP-to-country source; no vendor is chosen and
     * no database ships with the service, so {@code AddressGeography} has one
     * implementation that resolves nothing.
     *
     * <p>It is listed rather than omitted so that every assessment records that it could
     * not be evaluated. A missing signal that scored zero would be indistinguishable from
     * one that looked and found nothing — see {@code SignalOutcome}.
     */
    GEOGRAPHY_MISMATCH
}
