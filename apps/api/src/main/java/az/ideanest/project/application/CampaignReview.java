package az.ideanest.project.application;

import az.ideanest.project.domain.ChecklistResult;
import az.ideanest.project.domain.Project;

/**
 * Everything the review-and-launch screen is: where the campaign is, what is
 * still missing, and what moderation last said about it.
 *
 * <p>One value read in one transaction, and that is the point. The three are
 * consistent with each other by construction — a checklist evaluated from one
 * snapshot beside a state read from another would eventually tell a creator their
 * campaign is incomplete and already approved.
 *
 * @param moderation null when platform staff have never decided anything about
 *     this campaign, which is every campaign until its first submission
 */
public record CampaignReview(Project project, ChecklistResult checklist, ModerationOutcome moderation) {
}
