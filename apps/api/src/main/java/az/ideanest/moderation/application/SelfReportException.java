package az.ideanest.moderation.application;

import java.util.UUID;

/**
 * The reporter and the reported are the same account.
 *
 * <p>Refused rather than accepted-and-ignored, because the alternative is a row in
 * the queue that costs a moderator the same attention as a real complaint and can
 * never be acted on. V23 holds the same rule as
 * {@code content_reports_reporter_is_not_the_target}, for the reason every rule in
 * this module is in both places.
 */
public class SelfReportException extends RuntimeException {

    private final transient UUID accountId;

    public SelfReportException(UUID accountId) {
        super("Account " + accountId + " cannot report itself");
        this.accountId = accountId;
    }

    public UUID accountId() {
        return accountId;
    }
}
