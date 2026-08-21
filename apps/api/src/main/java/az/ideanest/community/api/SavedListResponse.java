package az.ideanest.community.api;

import az.ideanest.community.application.SavedCampaign;
import az.ideanest.community.application.SavedCampaignPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * §10.2's {@code GET /v1/me/saved}: one page of the caller's saved campaigns.
 *
 * <p><strong>{@code nextCursor} is null on the last page rather than absent or empty.</strong>
 * A client tests one thing — is there a cursor — and the three-way distinction between null,
 * missing and {@code ""} is exactly the sort of thing that gets handled two ways in two clients.
 *
 * @param items the campaigns, newest save first
 * @param nextCursor the opaque value to pass back as {@code ?cursor=}, or null at the end of
 *     the list
 */
public record SavedListResponse(List<Item> items, String nextCursor) {

    public static SavedListResponse of(SavedCampaignPage page) {
        return new SavedListResponse(
                page.items().stream().map(Item::of).toList(),
                page.next() == null ? null : page.next().encode());
    }

    /**
     * One saved campaign.
     *
     * <p><strong>The two slugs rather than a built URL</strong>, as every other campaign
     * reference in this API does: the path is the client's to construct, because the web app
     * and the mobile app do not agree on what a campaign link looks like. Both are null
     * together when the campaign has no addressable public page, which
     * {@code ProjectSummary.hasPublicPath} decides.
     *
     * <p>No cover image, no funding total and no deadline. {@code BackerSignalService#saved}
     * names that gap and says what closing it needs — a campaign card belongs to the discovery
     * module, and building one here would mean this module reading {@code projects}.
     */
    public record Item(UUID projectId, String title, String creatorSlug, String projectSlug, Instant savedAt) {

        static Item of(SavedCampaign campaign) {
            return new Item(
                    campaign.projectId(),
                    campaign.title(),
                    campaign.creatorSlug(),
                    campaign.projectSlug(),
                    campaign.savedAt());
        }
    }
}
