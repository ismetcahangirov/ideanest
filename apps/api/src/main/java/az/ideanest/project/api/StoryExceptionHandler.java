package az.ideanest.project.api;

import az.ideanest.project.domain.StoryDocumentInvalidException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A story document the server will not store, as an RFC 9457 problem detail.
 *
 * <p><strong>Why this advice covers {@link ProjectController} too.</strong> The
 * story is written through {@code PATCH /v1/projects/{id}} along with everything
 * else (contract §5), so a malformed document is refused by that controller — and
 * an advice is scoped to controllers rather than to exception packages. The
 * alternative was to add this exception to {@link ProjectExceptionHandler}, which
 * is the one line three sibling issues in flight would all have edited. Two
 * advices matching one controller is ordinary Spring: the resolver walks them and
 * takes the first with a method for the exception.
 *
 * <p>No {@code @Order}, and that is deliberate rather than an omission.
 * {@link StoryDocumentInvalidException} extends {@code RuntimeException} and not
 * {@code IllegalArgumentException} precisely so that no handler in
 * {@link ProjectExceptionHandler} matches it — there is nothing for an ordering to
 * decide. Had it extended {@code IllegalArgumentException} it would have been
 * answered as {@code INVALID_REQUEST} by whichever advice happened to sort first,
 * losing both the contract's code and the path a creator needs.
 *
 * <p>This class handles exactly one exception for the same reason: everything else
 * the story endpoints raise is scoped to {@link StoryVersionController} alone and
 * lives in {@link StoryVersionExceptionHandler}. Handling {@code
 * ProjectNotFoundException} here as well would put a second translation of it in
 * front of {@link ProjectController}, and the two would drift.
 */
@RestControllerAdvice(assignableTypes = {StoryVersionController.class, ProjectController.class})
public class StoryExceptionHandler {

    /**
     * 400 for a document that is not a story document.
     *
     * <p>Its own code rather than {@code PROJECT_FIELD_INVALID}, per contract §5:
     * an editor that receives this knows to look at the document rather than at a
     * form field, and the two are different screens.
     *
     * <p>The path is in {@code meta} so the editor can put the message beside the
     * block it is about. A story is hundreds of blocks long by the time it is worth
     * publishing, and "the story is invalid" would leave a creator scrolling
     * through it hunting for the image whose description they never wrote.
     */
    @ExceptionHandler(StoryDocumentInvalidException.class)
    public ProblemDetail handleInvalidDocument(StoryDocumentInvalidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/story-document-invalid"));
        problem.setTitle("Invalid story");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "STORY_DOCUMENT_INVALID");
        // Named `path` rather than `field`: it points inside a document —
        // `blocks[7].alt` — and calling it a field would invite a client to look for
        // a form control with that name.
        problem.setProperty("meta", Map.of("path", exception.path()));
        // Also under `errors`, keyed by the field of `ProjectEdit` this is about, so
        // that a client which already maps this module's 400s onto its form has
        // somewhere truthful to put the message.
        problem.setProperty("errors", Map.of("story", exception.getMessage()));
        return problem;
    }
}
