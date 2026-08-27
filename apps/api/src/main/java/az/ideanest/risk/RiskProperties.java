package az.ideanest.risk;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the fraud signals count as suspicious — issue #108.
 *
 * <p>Everything is defaulted in code rather than in {@code application.yml}, for the
 * reason {@code NotificationProperties} and {@code AbuseProperties} both give: a
 * deployment that configures none of this has to start, and the value it starts with has
 * to be one somebody argued for rather than a zero left behind by binding.
 *
 * <p><strong>The numbers here are a starting point and they are wrong.</strong> They are
 * not measured, because there is nothing to measure yet — the platform has no chargeback
 * history and #60 has not chosen a provider to have one with. They are set so that the
 * queue is quiet on ordinary behaviour and noisy on card testing, and the whole reason
 * they are configuration is that the first month of real pledges will move them.
 *
 * @param velocity how much activity in how long counts as fast
 * @param newAccountAge under which an account is new. Hours rather than days: everybody's
 *     account is new once, and a window measured in days flags a week of legitimate
 *     signups
 * @param weights what each signal contributes when it fires
 * @param reviewAtScore the score at which a person should look. Below it the assessment is
 *     still written — the record of what was noticed is the point — and nobody is asked to
 *     read it
 */
@ConfigurationProperties(prefix = "ideanest.risk")
public record RiskProperties(Velocity velocity, Duration newAccountAge, Weights weights, int reviewAtScore) {

    private static final Duration DEFAULT_NEW_ACCOUNT_AGE = Duration.ofHours(24);

    /**
     * Fifty out of a hundred: two signals of the heavier kind, or three of any kind.
     *
     * <p>One signal alone never reaches it, and that is the property worth having. Each of
     * these fires on behaviour that is unremarkable in isolation — a new account, a new
     * address, a busy afternoon — and a queue that flagged every new account would be a
     * queue nobody reads by the second week.
     */
    private static final int DEFAULT_REVIEW_AT_SCORE = 50;

    public RiskProperties {
        velocity = velocity == null ? Velocity.defaults() : velocity;
        newAccountAge = newAccountAge == null ? DEFAULT_NEW_ACCOUNT_AGE : newAccountAge;
        weights = weights == null ? Weights.defaults() : weights;
        reviewAtScore = reviewAtScore == 0 ? DEFAULT_REVIEW_AT_SCORE : reviewAtScore;

        if (!newAccountAge.isPositive()) {
            throw new IllegalArgumentException("An account is new for some length of time");
        }
        if (reviewAtScore < 1 || reviewAtScore > 100) {
            throw new IllegalArgumentException("A review threshold is a score between 1 and 100");
        }
    }

    /**
     * @param window how far back the counts look. Forty-eight hours, matching §6.2's
     *     ranking window, so that "recent" means one thing on this platform
     * @param pledgesPerAccount pledges by one account in the window before it is fast. Six
     *     is generous: a backer who finds the platform and supports five campaigns in an
     *     evening is a good afternoon for everybody, and the seventh is where it stops
     *     looking like enthusiasm
     * @param pledgesPerAddress pledges from one address in the window, across every
     *     account. Higher than the per-account figure and not lower, because one address
     *     is a household, an office, or a carrier NAT with a city behind it
     */
    public record Velocity(Duration window, int pledgesPerAccount, int pledgesPerAddress) {

        private static final Duration DEFAULT_WINDOW = Duration.ofHours(48);

        private static final int DEFAULT_PLEDGES_PER_ACCOUNT = 6;

        private static final int DEFAULT_PLEDGES_PER_ADDRESS = 12;

        static Velocity defaults() {
            return new Velocity(DEFAULT_WINDOW, DEFAULT_PLEDGES_PER_ACCOUNT, DEFAULT_PLEDGES_PER_ADDRESS);
        }

        public Velocity {
            window = window == null ? DEFAULT_WINDOW : window;
            pledgesPerAccount = pledgesPerAccount == 0 ? DEFAULT_PLEDGES_PER_ACCOUNT : pledgesPerAccount;
            pledgesPerAddress = pledgesPerAddress == 0 ? DEFAULT_PLEDGES_PER_ADDRESS : pledgesPerAddress;

            if (!window.isPositive()) {
                throw new IllegalArgumentException("A velocity window is a positive duration");
            }
            if (pledgesPerAccount < 1 || pledgesPerAddress < 1) {
                throw new IllegalArgumentException("A velocity threshold is at least one pledge");
            }
        }
    }

    /**
     * What each signal is worth.
     *
     * <p>They add rather than multiply — {@code RiskScorer} argues why — so these are
     * directly comparable, and two of them summing past {@link #reviewAtScore} is what puts
     * a pledge in the queue.
     *
     * @param pledgeVelocityAccount the heaviest, because card testing is the pattern with
     *     money behind it
     * @param pledgeVelocityAddress as heavy: it catches the same pattern spread over
     *     accounts, which is the version that defeats the first
     * @param newAccount the lightest. Not evidence on its own — see {@code RiskSignal}
     * @param unfamiliarAddress middling. §4.10 already thinks an unfamiliar address is
     *     worth telling somebody about
     * @param geographyMismatch middling, and never applied: no deployment can evaluate it
     */
    public record Weights(
            int pledgeVelocityAccount,
            int pledgeVelocityAddress,
            int newAccount,
            int unfamiliarAddress,
            int geographyMismatch) {

        static Weights defaults() {
            return new Weights(30, 30, 10, 20, 20);
        }

        public Weights {
            pledgeVelocityAccount = pledgeVelocityAccount == 0 ? 30 : pledgeVelocityAccount;
            pledgeVelocityAddress = pledgeVelocityAddress == 0 ? 30 : pledgeVelocityAddress;
            newAccount = newAccount == 0 ? 10 : newAccount;
            unfamiliarAddress = unfamiliarAddress == 0 ? 20 : unfamiliarAddress;
            geographyMismatch = geographyMismatch == 0 ? 20 : geographyMismatch;

            for (int weight : new int[] {
                pledgeVelocityAccount, pledgeVelocityAddress, newAccount, unfamiliarAddress, geographyMismatch
            }) {
                if (weight < 0 || weight > 100) {
                    throw new IllegalArgumentException("A signal weight is between 0 and 100");
                }
            }
        }
    }
}
