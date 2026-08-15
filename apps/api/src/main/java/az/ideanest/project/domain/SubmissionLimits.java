package az.ideanest.project.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The bounds §5.3 leaves to configuration.
 *
 * <p>§5.3 states most of its numbers outright — sixty characters, sixty days,
 * five hundred characters — and states three of them as decisions somebody else
 * makes: the goal's "minimum and maximum are configurable", and a reward price is
 * "at least the smallest chargeable amount". Those three are here, passed into
 * {@link SubmissionChecklist} rather than compiled into it, because the answers
 * change without the rule changing.
 *
 * <p><strong>Why they are genuinely not constants.</strong> The smallest
 * chargeable amount belongs to the payment provider (§9.3) and to the currency;
 * it is a fact about somebody else's system that we happen to have to know. The
 * goal bounds are a commercial position — the smallest campaign worth the
 * platform's moderation time, and the largest one it is willing to underwrite —
 * and both move without a deployment. Hard-coding either would mean a release to
 * change a number that is not ours.
 *
 * <p>A value object rather than the properties record itself, so that
 * {@link SubmissionChecklist} stays a pure type with no Spring anywhere near it
 * and its tests can state a bound rather than construct a configuration.
 *
 * @param goalMinimum the smallest funding goal a campaign may be submitted with
 * @param goalMaximum the largest
 * @param rewardPriceMinimum the smallest amount the payment provider will charge,
 *     which is the floor §5.3 puts under every reward tier
 */
public record SubmissionLimits(BigDecimal goalMinimum, BigDecimal goalMaximum, BigDecimal rewardPriceMinimum) {

    public SubmissionLimits {
        Objects.requireNonNull(goalMinimum, "A minimum goal is required");
        Objects.requireNonNull(goalMaximum, "A maximum goal is required");
        Objects.requireNonNull(rewardPriceMinimum, "A minimum reward price is required");

        if (goalMinimum.signum() <= 0 || rewardPriceMinimum.signum() <= 0) {
            // Zero is not a floor, it is the absence of one, and a goal of zero is
            // met before it is announced. Refused at start-up, where an operator
            // sees it, rather than by every checklist afterwards.
            throw new IllegalArgumentException("A submission bound is above zero");
        }
        if (goalMaximum.compareTo(goalMinimum) < 0) {
            // Inverted bounds are a configuration typo that would make every
            // campaign unsubmittable with a message naming neither number.
            throw new IllegalArgumentException("The maximum goal cannot be below the minimum");
        }
    }
}
