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
 * <p>The creator is an id and nothing else. {@code CampaignDirectory} resolves the names
 * through {@code UserAccounts} in one lookup per page. {@link CampaignDirectoryRows} does
 * join {@code users} when there is a search term, and it still does not select a name from
 * it: what crosses in SQL is a predicate, and the name crosses through the user module's
 * own front door.
 *
 * <p><strong>A record since #404, where it was a Spring Data projection interface.</strong>
 * The four {@code @Query} methods that produced it became one assembled statement when the
 * directory gained a search and a creator filter — see {@link CampaignDirectoryRows} for
 * why sixteen variants was the alternative — and a hand-written {@code RowMapper} needs a
 * type it can construct. Nothing outside this module ever saw either shape.
 *
 * @param goalAmount null on a draft that has not said what it needs yet
 * @param launchedAt null for everything that has never been live
 * @param deadline null until the campaign launches, and frozen from that moment
 * @param createdAt when the campaign was created, which is the order this list is read in
 */
public record CampaignDirectoryRow(
        UUID projectId,
        String title,
        String slug,
        String state,
        Instant createdAt,
        Instant launchedAt,
        Instant deadline,
        BigDecimal goalAmount,
        String currency,
        BigDecimal pledgedAmount,
        int backersCount,
        UUID creatorId) {}
