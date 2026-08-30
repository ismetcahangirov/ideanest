package az.ideanest.media.api;

import az.ideanest.media.application.MediaLibrary;
import az.ideanest.media.domain.MediaAsset;
import java.time.Instant;
import java.util.UUID;

/** What the media endpoints answer — the media pipeline design of 2026-08-30. */
public final class MediaResponses {

    private MediaResponses() {}

    /**
     * The address the browser uploads to.
     *
     * <p>{@code maxBytes} is repeated here even though the client sent the size it intends
     * to upload: the ceiling is this platform's and a client that had to guess it would
     * either refuse files this platform accepts or let somebody watch a twenty-megabyte
     * upload finish before being told it was too large.
     *
     * @param mediaId the identifier to complete, poll and eventually attach
     * @param uploadUrl a presigned {@code PUT}. A credential — anybody holding it may write
     *     this one object until it expires
     * @param expiresAt when it stops working
     * @param maxBytes the ceiling, so the form can refuse before uploading
     */
    public record Upload(UUID mediaId, String uploadUrl, Instant expiresAt, long maxBytes) {

        static Upload of(MediaLibrary.MediaUpload upload) {
            return new Upload(
                    upload.mediaId(), upload.uploadUrl().toString(), upload.expiresAt(), upload.maxBytes());
        }
    }

    /**
     * One upload's state.
     *
     * <p>Everything after {@code status} is null until the image is ready, and
     * {@code failureReason} is null unless it failed. That is the shape the editor polls: a
     * client renders a spinner while there is neither, the image when there is a URL, and a
     * translated message when there is a reason.
     *
     * <p><strong>{@code failureReason} is a code and not a sentence.</strong> The words a
     * creator reads are in the message catalogue with every other string they read — a
     * message assembled here would be one that cannot be translated, on a form that exists
     * in four languages.
     *
     * @param width the measured width. This is the number the whole pipeline was built for:
     *     it used to be whatever the browser said, which {@code SubmissionChecklist} notes a
     *     client could make up
     */
    public record Media(
            UUID id,
            String status,
            String url,
            Integer width,
            Integer height,
            String blurDataUrl,
            String failureReason) {

        static Media of(MediaAsset asset, String url) {
            return new Media(
                    asset.getId(),
                    asset.getStatus().name(),
                    url,
                    asset.getWidth().orElse(null),
                    asset.getHeight().orElse(null),
                    asset.getBlurDataUrl().orElse(null),
                    asset.getFailureReason().map(Enum::name).orElse(null));
        }
    }
}
