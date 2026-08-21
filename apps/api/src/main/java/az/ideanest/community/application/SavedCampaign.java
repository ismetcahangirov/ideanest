package az.ideanest.community.application;

import az.ideanest.community.domain.Save;
import az.ideanest.shared.project.ProjectSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /v1/me/saved}: a campaign somebody saved, and when.
 *
 * <p><strong>The campaign's half comes from {@code ProjectSummaries} and is therefore a title
 * and a path, not a card.</strong> {@code BackerSignalService#saved} states the gap in full:
 * a cover image, a funding total and a deadline live in the discovery module's
 * {@code ProjectCard}, and assembling one here would mean a module reading another's table.
 *
 * @param projectId the campaign
 * @param title its name, as it is now. <strong>Not as it was when it was saved</strong>, which
 *     is the opposite of the rule {@code NotificationRequest} follows and is right for the
 *     opposite reason: a notification reports something that happened, and a saved list is a
 *     set of live links somebody is about to click
 * @param creatorSlug the creator's half of the public path, or null when the campaign has no
 *     addressable page
 * @param projectSlug the campaign's half, or null with it. Whole or absent together
 * @param savedAt when this account saved it, which is what the list is ordered by and half of
 *     the cursor for the next page
 */
public record SavedCampaign(UUID projectId, String title, String creatorSlug, String projectSlug, Instant savedAt) {

    /**
     * One row, or null when the campaign behind it could not be found.
     *
     * <p>Null rather than a row with an empty title: a saved campaign that has been hard
     * deleted is gone, and showing its identifier with no name would be a link to nothing
     * dressed as a campaign. The caller drops it from the page — see
     * {@code BackerSignalService#saved} for why that is preferable to failing the read.
     */
    static SavedCampaign of(Save row, ProjectSummary campaign) {
        if (campaign == null) {
            return null;
        }
        return new SavedCampaign(
                row.getProjectId(),
                campaign.title(),
                campaign.hasPublicPath() ? campaign.creatorSlug() : null,
                campaign.hasPublicPath() ? campaign.slug() : null,
                row.getCreatedAt());
    }
}
