package az.ideanest.risk.application;

import az.ideanest.risk.domain.RiskSignal;
import az.ideanest.risk.domain.SignalOutcome;

/**
 * One signal's verdict — issue #108.
 *
 * <p>The weight is carried even when the signal did not fire, and that is not redundancy:
 * the queue shows a reader what the assessment was <em>capable</em> of scoring, and a
 * finding that omitted its weight when clear would make a re-weighting invisible in the
 * history. It is what makes an old assessment readable after somebody has tuned the
 * numbers.
 *
 * @param detail one short sentence for a person reading the queue. <strong>Never an
 *     address, an email, or a name</strong> — §17.4 keeps personal data out of columns read
 *     by people who are not the person, and this one ends up in a jsonb document on a row
 *     with no retention rule
 */
public record RiskFinding(RiskSignal signal, SignalOutcome outcome, int weight, String detail) {

    public static RiskFinding fired(RiskSignal signal, int weight, String detail) {
        return new RiskFinding(signal, SignalOutcome.FIRED, weight, detail);
    }

    public static RiskFinding clear(RiskSignal signal, int weight) {
        return new RiskFinding(signal, SignalOutcome.CLEAR, weight, null);
    }

    public static RiskFinding unavailable(RiskSignal signal, int weight, String why) {
        return new RiskFinding(signal, SignalOutcome.UNAVAILABLE, weight, why);
    }
}
