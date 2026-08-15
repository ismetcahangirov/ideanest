package az.ideanest.project.domain;

import java.util.Objects;

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
 * <p>When the media module lands this becomes a reference to a {@code media}
 * row and the three columns are dropped in a later release. Keeping the value
 * object means that change is behind one type rather than spread across the
 * service and response mapping.
 */
public record CoverImage(String url, int width, int height) {

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
