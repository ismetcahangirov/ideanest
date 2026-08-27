package az.ideanest.risk.application;

import az.ideanest.risk.domain.RiskDecision;
import java.util.List;

/**
 * What {@link RiskScorer} produced — issue #108.
 *
 * @param score 0 to 100, clamped. An ordering rather than a probability: nothing here
 *     claims that 60 is twice as likely to be fraud as 30, and treating it that way is the
 *     mistake that turns a triage aid into a policy
 * @param decision what to do about it, from the configured thresholds
 * @param findings every signal, in the order they were evaluated, including the ones that
 *     found nothing. All of them, because "which signals were even considered" is the
 *     second question anybody asks
 * @param signalsUnavailable how many could not be evaluated. Separate from the score, so a
 *     low one cannot be read as a clean bill of health
 */
public record RiskScore(
        int score, RiskDecision decision, List<RiskFinding> findings, int signalsUnavailable) {}
