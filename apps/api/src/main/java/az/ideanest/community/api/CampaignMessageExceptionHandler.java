package az.ideanest.community.api;

import az.ideanest.community.application.InvalidSignalCursorException;
import az.ideanest.community.domain.MessageContentInvalidException;
import az.ideanest.pledge.application.BackerSegmentNotFoundException;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * What the messaging endpoints refuse, as RFC 9457 problem details (§10.4).
 *
 * <p>Scoped to {@link CampaignMessageController}, for {@code CommentExceptionHandler}'s reason:
 * every advice in this codebase names its controllers, so a refusal is translated once per
 * endpoint that can raise it and there is no ambient advice for the next reader to go looking
 * for.
 *
 * <p>The cost of that convention is this file: four of the five handlers below are the same
 * bodies {@code BackerReportExceptionHandler} produces, because this endpoint raises the same
 * exceptions from the same shared contracts. The alternative — one global advice for
 * {@code shared.access} — was rejected before this issue, on the grounds that the right status
 * genuinely differs by endpoint: a 404 for a campaign is right where enumeration is the risk and
 * wrong where the caller is already on the team.
 */
@RestControllerAdvice(assignableTypes = CampaignMessageController.class)
public class CampaignMessageExceptionHandler {

    /**
     * 404 for a campaign that does not exist and for one this caller has no part in, identically.
     *
     * <p>The same answer the backer report gives, and with the same reason: a 403 would confirm
     * to somebody enumerating identifiers that a campaign exists <em>and</em> has backers worth
     * writing to.
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleProjectNotFound(ProjectNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-found"));
        problem.setTitle("No such project");
        problem.setDetail("That project does not exist.");
        problem.setProperty("code", "PROJECT_NOT_FOUND");
        return problem;
    }

    /**
     * 403 for a collaborator who is party to the campaign without the capability this needs.
     *
     * <p>Not a 404: they were invited, they can already see the campaign, and there is nothing
     * left to hide from them. <strong>Which capability was missing is not in the body</strong>,
     * and that is deliberate here rather than copied: this endpoint needs
     * {@code PUBLISH_UPDATES} always and {@code VIEW_FINANCES} only for a segment, so naming the
     * missing one would tell a collaborator which grants exist to be asked for — and the answer
     * is the same either way, which is to ask whoever manages the campaign's team.
     */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted");
        problem.setDetail("Your grant on this campaign does not include messaging its backers.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");
        return problem;
    }

    /**
     * 404 for a segment that does not exist on this campaign.
     *
     * <p>Translated here as well as by the pledge module's own advice, because that one is
     * scoped to that module's controllers: an exception crossing an application-layer boundary
     * arrives at a controller whose advice has never heard of it, and the alternative is a 500
     * for a creator who picked a segment somebody else had just deleted.
     */
    @ExceptionHandler(BackerSegmentNotFoundException.class)
    public ProblemDetail handleSegmentNotFound(BackerSegmentNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/backer-segment-not-found"));
        problem.setTitle("No such segment");
        problem.setDetail("That segment does not exist on this campaign.");
        problem.setProperty("code", "BACKER_SEGMENT_NOT_FOUND");
        return problem;
    }

    /**
     * 422 for a subject or body that is empty or too long.
     *
     * <p>The bound travels in {@code meta} so a client can show a counter rather than teach the
     * rule by refusing a submission somebody has already written.
     */
    @ExceptionHandler(MessageContentInvalidException.class)
    public ProblemDetail handleContentInvalid(MessageContentInvalidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setType(URI.create("https://ideanest.az/problems/message-content-invalid"));
        problem.setTitle("The message will not do");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "MESSAGE_CONTENT_INVALID");
        problem.setProperty("meta", Map.of("field", exception.field(), "maxLength", exception.maxLength()));
        return problem;
    }

    /** 400 for a page cursor this endpoint did not issue. See {@code InvalidSignalCursorException}. */
    @ExceptionHandler(InvalidSignalCursorException.class)
    public ProblemDetail handleInvalidCursor(InvalidSignalCursorException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/invalid-cursor"));
        problem.setTitle("Invalid cursor");
        problem.setDetail("That page cursor is not one this endpoint issued.");
        problem.setProperty("code", "INVALID_CURSOR");
        return problem;
    }
}
