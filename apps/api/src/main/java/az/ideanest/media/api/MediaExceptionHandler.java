package az.ideanest.media.api;

import az.ideanest.media.application.MediaFailedException;
import az.ideanest.media.application.MediaNotFoundException;
import az.ideanest.media.application.ObjectStoreUnavailableException;
import az.ideanest.media.application.UploadsUnavailableException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * What uploading refuses, as RFC 9457 problem details (§10.4) — the media pipeline design of
 * 2026-08-30.
 *
 * <p>Scoped to this module's controller rather than declared globally, as every other
 * handler in this service is: an advice that catches a broad type across the whole
 * application turns a bug somewhere else into a tidy 4xx and hides it.
 *
 * <p>Every {@code code} here is a name the web client already has a translation for. A
 * {@code detail} is English and is for whoever is reading a log — the sentence a creator
 * sees is looked up from the code, on a form that exists in four languages.
 */
@RestControllerAdvice(assignableTypes = MediaController.class)
public class MediaExceptionHandler {

    /** 404 for an upload that does not exist and for one belonging to somebody else. */
    @ExceptionHandler(MediaNotFoundException.class)
    public ProblemDetail handleNotFound(MediaNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/media-not-found"));
        problem.setTitle("No such upload");
        // The same answer whether it is absent or somebody else's, deliberately: telling
        // them apart lets anybody holding a token confirm an identifier they guessed.
        problem.setDetail("That upload does not exist.");
        problem.setProperty("code", "MEDIA_NOT_FOUND");
        return problem;
    }

    /** 400 for a file this platform will not take. The reason is the {@code code}. */
    @ExceptionHandler(MediaFailedException.class)
    public ProblemDetail handleRefused(MediaFailedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/media-refused"));
        problem.setTitle("That file cannot be used");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", exception.reason().name());
        problem.setProperty("field", "file");
        return problem;
    }

    /**
     * 503 for a deployment with no storage configured.
     *
     * <p>Not a 500. Nothing went wrong: this is a deployment that has not been given a
     * bucket, which {@code MediaProperties} argues is a supported state rather than a
     * misconfiguration to crash on. The distinction is what somebody reading the response
     * needs — a 500 says report a bug, and this says ask an operator.
     */
    @ExceptionHandler(UploadsUnavailableException.class)
    public ProblemDetail handleUnavailable(UploadsUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(URI.create("https://ideanest.az/problems/uploads-unavailable"));
        problem.setTitle("Uploads are not available");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "UPLOADS_UNAVAILABLE");
        return problem;
    }

    /**
     * 503 for a store that is configured and could not be reached.
     *
     * <p>The same status as above and a different code, because the two are different
     * things to an operator: one has never been set up and the other has stopped answering.
     * A client's behaviour is identical — wait and try again — which is why they share a
     * status.
     */
    @ExceptionHandler(ObjectStoreUnavailableException.class)
    public ProblemDetail handleStoreDown(ObjectStoreUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(URI.create("https://ideanest.az/problems/media-storage-unreachable"));
        problem.setTitle("Storage is not answering");
        // Deliberately not the exception's message, which names a bucket and a key.
        problem.setDetail("Image storage could not be reached. Please try again shortly.");
        problem.setProperty("code", "MEDIA_STORAGE_UNREACHABLE");
        return problem;
    }
}
