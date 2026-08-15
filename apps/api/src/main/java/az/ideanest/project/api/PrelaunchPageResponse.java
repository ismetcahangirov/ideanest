package az.ideanest.project.api;

import az.ideanest.project.application.PrelaunchService.PrelaunchPage;
import az.ideanest.project.domain.Project;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * A campaign as its pre-launch page shows it, to anybody.
 *
 * <p><strong>This is not the public project projection, and the line is drawn on
 * purpose.</strong> {@code GET /v1/projects/{creatorSlug}/{projectSlug}} in §10.2
 * is the public project page, and it belongs to another epic; nothing in this
 * issue may pre-empt its shape. What is here is exactly the set of things a
 * pre-launch page renders and nothing that would still be needed once that
 * endpoint exists.
 *
 * <p>So the following are <strong>deliberately absent</strong>:
 *
 * <ul>
 *   <li><strong>The creator.</strong> Name, slug, avatar, and the rest are the
 *       public profile projection, which belongs to the profile epic. A pre-launch
 *       page that named its creator would be the first field of that projection,
 *       decided here by accident.
 *   <li><strong>The goal, the amount pledged, the backer count, and the
 *       deadline.</strong> None of them exists yet on a campaign that has not
 *       launched — and publishing a goal before the campaign is approved would put
 *       a figure in front of the public that moderation may still send back.
 *   <li><strong>The story, the rewards, the FAQ, the category.</strong> Each has
 *       its own endpoint in §10.2, and a pre-launch page is an announcement rather
 *       than a campaign page in miniature.
 * </ul>
 *
 * <p>What is left is the promise: the title, the summary, the cover image, when it
 * is expected to open, and how many people are waiting. Those are the same fields
 * the campaign page will carry, read from the same columns — a dedicated
 * "pre-launch headline" was considered and rejected, because a page that promises
 * something different from the campaign it becomes is a promise the follower did
 * not sign up for.
 *
 * <p>Nulls are omitted here, unlike {@link ProjectEdit}: this response feeds a page
 * rather than a form, so "absent" and "empty" mean the same thing to its only
 * reader, and the service's {@code non_null} default applies.
 *
 * @param followerCount how many people have asked to be told. Public because it is
 *     the one number that makes a pre-launch page work — "412 people are waiting"
 *     is the whole social proof of a campaign with no pledges yet
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrelaunchPageResponse(
        UUID id,
        String slug,
        String state,
        String title,
        String blurb,
        CoverImageBody coverImage,
        Instant scheduledLaunchAt,
        long followerCount) {

    public static PrelaunchPageResponse of(PrelaunchPage page) {
        Project project = page.project();
        return new PrelaunchPageResponse(
                project.getId(),
                project.getSlug(),
                project.getState().name(),
                project.getTitle(),
                project.getBlurb(),
                CoverImageBody.of(project.getCoverImage()),
                project.getScheduledLaunchAt(),
                page.followerCount());
    }
}
