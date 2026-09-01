package az.ideanest.project.application;

import az.ideanest.project.domain.ProjectState;
import java.util.List;
import java.util.UUID;

/**
 * One page of the submission queue.
 *
 * @param state which state was asked for, echoed back so a client rendering tabs does
 *     not have to remember which request this answers
 * @param submissions the campaigns, oldest first
 * @param nextCursor what to pass as {@code after} for the following page, or null when
 *     this page was not full. A full page is the only honest signal that there may be
 *     more — see {@code CampaignSubmissionQueue}
 */
public record SubmissionQueuePage(ProjectState state, List<SubmittedCampaign> submissions, UUID nextCursor) {

    public SubmissionQueuePage {
        submissions = List.copyOf(submissions);
    }
}
