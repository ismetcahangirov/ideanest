package az.ideanest.project.api;

import az.ideanest.project.application.CampaignDirectoryPage;
import az.ideanest.project.application.DirectoryCampaign;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of the campaign directory, on the wire.
 *
 * <p>Its own record rather than the application's page serialised directly, which is
 * what {@code SubmissionQueueResponse} does next door and for the same reason: the shape
 * a client depends on should change when somebody means to change it, not when a field
 * is added to an internal type.
 *
 * @param state the filter this page was read with, or null for every campaign
 * @param campaigns the rows, newest first
 * @param nextCursor what to pass as {@code after} for the next page, or null at the end
 */
public record CampaignDirectoryResponse(String state, List<Campaign> campaigns, UUID nextCursor) {

    static CampaignDirectoryResponse of(CampaignDirectoryPage page) {
        return new CampaignDirectoryResponse(
                page.state() == null ? null : page.state().name(),
                page.campaigns().stream().map(Campaign::of).toList(),
                page.nextCursor());
    }

    /**
     * @param projectId also the cursor: the next page is asked for with this value
     * @param goal null on a draft that has not said what it needs
     * @param pledged always present, because nothing raised is zero rather than unknown
     */
    public record Campaign(
            UUID projectId,
            String title,
            String slug,
            String state,
            Instant createdAt,
            Instant launchedAt,
            Instant deadline,
            Money goal,
            Money pledged,
            int backersCount,
            UUID creatorId,
            String creatorName,
            String creatorSlug) {

        static Campaign of(DirectoryCampaign campaign) {
            return new Campaign(
                    campaign.projectId(),
                    campaign.title(),
                    campaign.slug(),
                    campaign.state().name(),
                    campaign.createdAt(),
                    campaign.launchedAt(),
                    campaign.deadline(),
                    campaign.goal(),
                    campaign.pledged(),
                    campaign.backersCount(),
                    campaign.creatorId(),
                    campaign.creatorName(),
                    campaign.creatorSlug());
        }
    }
}
