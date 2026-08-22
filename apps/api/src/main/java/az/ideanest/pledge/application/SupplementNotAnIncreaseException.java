package az.ideanest.pledge.application;

import java.util.UUID;

/**
 * The purchase would not cost the backer anything more — §4.8's PM-09 and PM-10.
 *
 * <p><strong>A downgrade is not a negative supplement.</strong> Money that has been
 * collected comes back through #67's refunds, which have a reason code, an audit row,
 * and a payment provider behind them; a negative amount in {@code pledge_supplements}
 * would be a payment sitting in a table a collection run reads, and V39's
 * {@code pledge_supplements_amount_is_positive} refuses one anyway.
 *
 * <p>It is also the answer to the smaller cases that would otherwise be silent: an
 * upgrade to the tier the pledge already has, and a purchase of add-ons that cost
 * nothing.
 */
public class SupplementNotAnIncreaseException extends RuntimeException {

    public SupplementNotAnIncreaseException(UUID pledgeId) {
        super("The purchase on pledge " + pledgeId + " costs no more than what it already has");
    }
}
