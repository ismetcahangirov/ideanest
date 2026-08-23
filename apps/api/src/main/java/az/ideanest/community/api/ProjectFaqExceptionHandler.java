package az.ideanest.community.api;

import az.ideanest.community.application.FaqNotFoundException;
import az.ideanest.community.application.FaqOrderIncompleteException;
import az.ideanest.community.application.TooManyFaqsException;
import az.ideanest.community.domain.FaqContentInvalidException;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * What the FAQ endpoints refuse, as RFC 9457 problem details (§10.4).
 *
 * <p>Scoped to these two controllers rather than declared globally, exactly as
 * {@code ProjectUpdateExceptionHandler} is: a second translation of
 * {@code ProjectNotFoundException} in front of a controller that already has one is two
 * bodies for one refusal, and the drift shows up as the same failure answering
 * differently depending on which endpoint produced it.
 *
 * <p>Two of these are the project module's exceptions, translated here rather than
 * reused. {@code ProjectProblems} — the class that writes the 403 for the project
 * module's own controllers — is package-private in that module's {@code api} package, and
 * making it public so that this module could call it would be a dependency between two
 * {@code api} layers, which is the one direction the module boundary does not permit at
 * all.
 *
 * <p>Every response carries a {@code code} as well as a status, per §10.4. The status
 * tells a client how to behave; the code tells it what happened, and it is what a client
 * branches on.
 */
@RestControllerAdvice(assignableTypes = {ProjectFaqController.class, PublicProjectFaqController.class})
public class ProjectFaqExceptionHandler {

    /**
     * 404 for a campaign that does not exist, for one that is not publicly visible, and
     * for one this caller has no relationship to.
     *
     * <p>Deliberately the same answer for all three, as everywhere else in the platform. A
     * draft is an unreleased product; telling a caller apart from "not there" and "not
     * yours" would turn this endpoint into an oracle for what other people are preparing,
     * and it would do it from a path that needs no token at all.
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
     * 404 for an entry that does not exist, and for one under a campaign this caller has
     * no part in.
     *
     * <p>Deliberately the same answer. See {@code FaqNotFoundException}: the single-entry
     * endpoints carry no campaign in the path, so a refusal that distinguished the two
     * would let anybody discover which identifiers are real without knowing whose
     * campaign they belong to.
     */
    @ExceptionHandler(FaqNotFoundException.class)
    public ProblemDetail handleFaqNotFound(FaqNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/faq-not-found"));
        problem.setTitle("No such FAQ entry");
        problem.setDetail("That FAQ entry does not exist.");
        problem.setProperty("code", "FAQ_NOT_FOUND");
        return problem;
    }

    /**
     * 403 for a collaborator who works on the campaign and may not manage its FAQ.
     *
     * <p>Not a 404: they were invited and can already read the campaign, so there is
     * nothing left to hide from them. The body names what would have authorised the
     * request and what they hold, so the client can disable the control rather than let
     * somebody write an answer and be refused at the end of it.
     */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted on this campaign");
        problem.setDetail("Your collaborator access on this campaign does not include that.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requiredAnyOf", exception.requiredAnyOf());
        meta.put("held", exception.held());
        problem.setProperty("meta", meta);
        return problem;
    }

    /**
     * 400 for a question or an answer the platform will not store.
     *
     * <p>Names the field rather than reporting the whole request as invalid: the message
     * is shown beside the input that caused it, and a creator who has just written four
     * hundred words should not have to guess which of two boxes the refusal is about.
     */
    @ExceptionHandler(FaqContentInvalidException.class)
    public ProblemDetail handleContentInvalid(FaqContentInvalidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/faq-content-invalid"));
        problem.setTitle("Invalid FAQ entry");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "FAQ_CONTENT_INVALID");
        problem.setProperty("meta", Map.of("field", exception.field()));
        return problem;
    }

    /**
     * 400 for a reorder that does not name every entry of the campaign exactly once.
     *
     * <p>Both halves of the disagreement are in {@code meta}, in the shape
     * {@code RewardExceptionHandler} already uses: what the request left out, and what it
     * named that does not belong — a repeat counting as the second. A client whose list
     * is stale can then say which entry it is missing rather than reporting the whole
     * drag as failed.
     */
    @ExceptionHandler(FaqOrderIncompleteException.class)
    public ProblemDetail handleOrderIncomplete(FaqOrderIncompleteException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/faq-order-incomplete"));
        problem.setTitle("Incomplete FAQ order");
        problem.setDetail("A reorder lists every FAQ entry of the campaign exactly once.");
        problem.setProperty("code", "FAQ_ORDER_INCOMPLETE");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("missing", identifiers(exception.missing()));
        meta.put("unexpected", identifiers(exception.unexpected()));
        problem.setProperty("meta", meta);
        return problem;
    }

    /**
     * 409 for the fifty-first entry.
     *
     * <p>A conflict rather than a bad request: the entry is perfectly valid and what
     * refuses it is how many already exist. The limit is in {@code meta} so a client can
     * say what the ceiling is instead of reporting a number it hard-coded.
     */
    @ExceptionHandler(TooManyFaqsException.class)
    public ProblemDetail handleTooManyFaqs(TooManyFaqsException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/too-many-faqs"));
        problem.setTitle("Too many FAQ entries");
        problem.setDetail("This campaign already holds the most FAQ entries a campaign may have.");
        problem.setProperty("code", "TOO_MANY_FAQS");
        problem.setProperty("meta", Map.of("limit", exception.limit()));
        return problem;
    }

    /**
     * Identifiers as strings, so that a null the client sent survives into the body it is
     * reported in rather than becoming the string "null" or disappearing.
     */
    private static List<String> identifiers(List<UUID> ids) {
        return ids.stream().map(id -> id == null ? null : id.toString()).toList();
    }
}
