package az.ideanest.moderation.application;

import az.ideanest.moderation.domain.ReportTargetType;
import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.project.application.PublicProjects;
import az.ideanest.user.application.UserAccounts;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Whether there is anything at that identifier worth reporting.
 *
 * <p><strong>The check is not optional, and it is not about correctness of
 * data.</strong> Without it the endpoint accepts a report about any UUID a client
 * cares to invent, which is two problems: the queue fills with rows a moderator
 * opens, cannot find anything behind, and closes; and the endpoint becomes an oracle
 * — a caller who could report an identifier and be told "accepted" would learn that
 * the identifier names something, which for a draft campaign is exactly what
 * {@code ProjectAccess} spends its class comment refusing to say.
 *
 * <p><strong>Each module answers for its own surface.</strong> A campaign is
 * resolved through {@code PublicProjects}, which already encodes §6.1's nine public
 * states and answers 404 for a draft and for a suspended campaign alike; an account
 * through {@code UserAccounts}, which excludes soft-deleted rows. Neither rule is
 * restated here, because a second copy of "which campaigns may a stranger see" is
 * the copy that forgets {@code SUSPENDED} the next time the list changes.
 *
 * <p><strong>A suspended campaign is deliberately unreportable.</strong> It reads
 * oddly for a safety feature until you notice what a suspension is: trust and safety
 * has already stopped that campaign, so a further report about it adds a row to the
 * queue and no information. The reporter gets the same 404 anybody else browsing
 * gets, which is consistent rather than special.
 */
@Service
public class ReportTargets {

    private final PublicProjects projects;
    private final UserAccounts accounts;

    public ReportTargets(PublicProjects projects, UserAccounts accounts) {
        this.projects = projects;
        this.accounts = accounts;
    }

    /**
     * Establishes that the target exists and may be named by a stranger.
     *
     * @throws ReportTargetNotFoundException when it does not exist, or exists and is
     *     not something this caller is allowed to know exists
     * @throws UnsupportedReportTargetException for a surface the platform has not
     *     built yet. Unreachable through today's two routes; see the exception for
     *     why it is here anyway
     */
    public void require(ReportTargetType targetType, UUID targetId) {
        switch (targetType) {
            case PROJECT -> requireProject(targetId);
            case USER -> requireAccount(targetId);
            case COMMENT, PROJECT_UPDATE -> throw new UnsupportedReportTargetException(targetType);
        }
    }

    private void requireProject(UUID projectId) {
        try {
            projects.requireVisible(projectId);
        } catch (ProjectNotFoundException e) {
            // Translated rather than propagated. The project module's advice is
            // scoped to the project module's controllers, so an exception of its
            // escaping here would reach the fallback handler and become a 500.
            throw new ReportTargetNotFoundException(ReportTargetType.PROJECT, projectId);
        }
    }

    private void requireAccount(UUID accountId) {
        accounts.findById(accountId)
                .orElseThrow(() -> new ReportTargetNotFoundException(ReportTargetType.USER, accountId));
    }
}
