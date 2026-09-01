package az.ideanest.project.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the console's campaign directory.
 *
 * <p>A projection rather than the entity, for the reason {@link SubmissionQueueRow}
 * gives: a list of campaigns is a list of facts about campaigns, and loading
 * {@code Project} would bring the story document with every row.
 *
 * <p><strong>Wider than the queue's row, and deliberately so.</strong> The queue answers
 * "what is waiting on me" and needs the note that came with the last decision. This
 * answers "what is on the platform", where the questions are how a campaign is doing and
 * when it opened — so the funding figures are here and the note is not.
 *
 * <p>The creator is an id and nothing else. {@code users} belongs to another module and
 * a join across that boundary in a native query would be this module reading a table it
 * does not own; {@code CampaignDirectory} resolves the names through
 * {@code UserAccounts} in one lookup per page.
 */
public interface CampaignDirectoryRow {

    UUID getProjectId();

    String getTitle();

    String getSlug();

    String getState();

    /** When the campaign was created, which is the order this list is read in. */
    Instant getCreatedAt();

    /** Null for everything that has never been live. */
    Instant getLaunchedAt();

    /** Null until the campaign launches, and frozen from that moment. */
    Instant getDeadline();

    /** Null on a draft that has not said what it needs yet. */
    BigDecimal getGoalAmount();

    String getCurrency();

    BigDecimal getPledgedAmount();

    int getBackersCount();

    UUID getCreatorId();
}
