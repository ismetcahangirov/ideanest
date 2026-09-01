package az.ideanest.project.application;

import az.ideanest.project.domain.ProjectState;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One campaign as the console's directory lists it.
 *
 * @param projectId also the cursor. The list is ordered by {@code (created_at, id)} and
 *     the repository asks for everything sorting after this campaign, so a caller pages
 *     by naming the last row it saw rather than by counting
 * @param title what the campaign calls itself
 * @param slug for the link to the campaign's own page
 * @param state where it is in §6.1
 * @param createdAt when the creator started it, which is the order of this list
 * @param launchedAt when it went live, or null if it never has
 * @param deadline when it closes, or null before it launches
 * @param goal what it asks for, or null on a draft that has not said yet
 * @param pledged what it has raised. Always a figure, because a campaign that has raised
 *     nothing has raised zero and a blank there would read as "not known"
 * @param backersCount how many people are behind it
 * @param creatorId who started it
 * @param creatorName their name, or null for an account §17.4 has anonymised. Null rather
 *     than a placeholder: inventing a name tells a reader there is somebody to write to
 * @param creatorSlug their public profile, on the same terms
 */
public record DirectoryCampaign(
        UUID projectId,
        String title,
        String slug,
        ProjectState state,
        Instant createdAt,
        Instant launchedAt,
        Instant deadline,
        Money goal,
        Money pledged,
        int backersCount,
        UUID creatorId,
        String creatorName,
        String creatorSlug) {}
