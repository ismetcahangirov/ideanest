package az.ideanest.project.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The campaign's cover image: a location and the dimensions it was measured at.
 *
 * <p><strong>Interim, and knowingly so.</strong> §13 describes a media pipeline
 * with an uploader, a {@code media} table, and transcoding state. None of it
 * exists, and §5.3 still makes a cover image of at least 1024×576 a submission
 * requirement — a checklist (#37) cannot check a field that is not there. So the
 * image is three columns on {@code projects} and this type is the one place that
 * knows they belong together.
 *
 * <p>The dimensions are stored rather than derived because deriving them means
 * fetching the image, and the checklist runs on every keystroke in the editor.
 * They are required alongside the URL for the same reason: a row that says an
 * image is present without saying how large it is cannot be checked against the
 * minimum, and the checklist would report a campaign submittable that is not.
 *
 * <p><strong>The media module has landed, and this is the expand half.</strong>
 * {@code mediaId} refers to the uploaded file when there is one, and is null for
 * every cover that predates the pipeline and for one supplied as a typed URL. The
 * three columns are still written in both cases, so every read path in the service
 * keeps working unchanged — that is what makes the contract a later release rather
 * than this one.
 *
 * <p>What the reference buys while both are present: the dimensions beside it were
 * <em>measured</em> rather than reported by a browser, and the media row carries
 * §13.1's blur placeholder. See the media pipeline design of 2026-08-30.
 */
public record CoverImage(String url, int width, int height, UUID mediaId) {

    /**
     * A cover from a typed URL, with no uploaded file behind it.
     *
     * <p>The shape every call site had before the pipeline, kept so that the change
     * is one field on the paths that set it rather than an edit to every path that
     * reads it.
     */
    public CoverImage(String url, int width, int height) {
        this(url, width, height, null);
    }

    public CoverImage {
        Objects.requireNonNull(url, "A cover image needs a location");
        if (url.isBlank()) {
            throw new IllegalArgumentException("A cover image needs a location");
        }
        if (width <= 0 || height <= 0) {
            // Zero or negative extent is not a small image, it is a measurement
            // that failed. Accepting it would put the failure in the database
            // and surface it as an unrenderable campaign page.
            throw new IllegalArgumentException("A cover image needs positive dimensions");
        }
    }
}
