package az.ideanest.project.api;

import az.ideanest.project.application.CoverImageSelection;
import az.ideanest.project.application.ProjectFieldRejectedException;
import az.ideanest.project.domain.CoverImage;
import java.util.UUID;

/**
 * The campaign's cover image, in a request and in a response.
 *
 * <p>One record for both directions because it is the same three values, and two
 * would drift the first time a field was added to one of them.
 *
 * <h2>Two ways in, and they carry different amounts of truth</h2>
 *
 * <p><strong>{@code mediaId} is the one to send.</strong> It names a file this creator
 * uploaded, and the server measured it — so the location and the dimensions come from the
 * image rather than from the request, and §13.1's blur placeholder comes with them. When it
 * is present the other three fields are ignored on the way in.
 *
 * <p><strong>{@code url} with its dimensions still works</strong>, and is what every campaign
 * predating the uploader has. Nothing on the server has seen that image: it cannot measure
 * it, and a client could claim any size it liked. That was one of the two reasons §5.3's
 * 1024×576 stopped refusing submissions — see {@code ChecklistRequirement.COVER_IMAGE_SIZE}.
 *
 * <p>On the way out all four are populated, {@code mediaId} being null for a cover that came
 * from a typed URL.
 *
 * @param url where the image is. Ignored when {@code mediaId} is given; not validated as
 *     reachable when it is not
 * @param width in pixels, as the client measured it. Ignored when {@code mediaId} is given
 * @param height likewise
 * @param mediaId an upload of this creator's that has finished processing
 */
public record CoverImageBody(String url, Integer width, Integer height, UUID mediaId) {

    /**
     * What was asked for, unresolved.
     *
     * <p>Deliberately does not build a {@link CoverImage}: for an upload the location and
     * dimensions are rows this layer has no business reading, and inventing placeholders for
     * the service to overwrite is how a half-resolved cover reaches the database.
     *
     * @throws ProjectFieldRejectedException when neither an upload nor a complete typed
     *     image was given. The database refuses the same combination, and refusing it here is
     *     what makes the answer a 400 naming the field
     */
    public CoverImageSelection toSelection() {
        if (mediaId != null) {
            return new CoverImageSelection.FromUpload(mediaId);
        }
        if (url == null || url.isBlank() || width == null || height == null) {
            throw new ProjectFieldRejectedException(
                    "coverImage", "A cover image needs an upload, or a location and its dimensions.");
        }
        return new CoverImageSelection.FromUrl(url.trim(), width, height);
    }

    /** Null in, null out: a campaign with no cover image yet. */
    public static CoverImageBody of(CoverImage coverImage) {
        return coverImage == null
                ? null
                : new CoverImageBody(
                        coverImage.url(), coverImage.width(), coverImage.height(), coverImage.mediaId());
    }
}
