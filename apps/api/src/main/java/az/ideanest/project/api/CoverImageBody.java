package az.ideanest.project.api;

import az.ideanest.project.application.ProjectFieldRejectedException;
import az.ideanest.project.domain.CoverImage;

/**
 * The campaign's cover image, in a request and in a response.
 *
 * <p>One record for both directions because it is the same three values, and two
 * would drift the first time a field was added to one of them.
 *
 * <p><strong>The dimensions are sent by the client, which is interim.</strong>
 * There is no media pipeline (§13) and no uploader, so nothing on the server has
 * ever seen the image: it cannot measure it, and a client could claim any size it
 * liked. That matters because §5.3 makes 1024×576 a submission requirement, so
 * today the requirement is checked against a number the client supplied. The media
 * module measures the file on upload and this becomes a reference to a
 * {@code media} row; until then the honest description is that this is a
 * self-declared size, and it is named as a gap rather than presented as a check.
 *
 * @param url where the image is. Not validated as reachable, for the same reason
 * @param width in pixels, as the client measured it
 * @param height in pixels, as the client measured it
 */
public record CoverImageBody(String url, Integer width, Integer height) {

    /**
     * @throws ProjectFieldRejectedException when the three values do not describe
     *     one image. The database refuses the same combination, and refusing it
     *     here is what makes the answer a 400 naming the field
     */
    public CoverImage toDomain() {
        if (url == null || url.isBlank() || width == null || height == null) {
            throw new ProjectFieldRejectedException(
                    "coverImage", "A cover image needs a location and its dimensions.");
        }
        return new CoverImage(url.trim(), width, height);
    }

    /** Null in, null out: a campaign with no cover image yet. */
    public static CoverImageBody of(CoverImage coverImage) {
        return coverImage == null
                ? null
                : new CoverImageBody(coverImage.url(), coverImage.width(), coverImage.height());
    }
}
