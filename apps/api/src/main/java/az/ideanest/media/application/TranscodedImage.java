package az.ideanest.media.application;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What came out of the transcoder — the media pipeline design of 2026-08-30.
 *
 * @param file the derived image, on disk, ready to be uploaded under its key
 * @param contentType {@code image/jpeg} or {@code image/png}. What the pipeline decided,
 *     never what the client declared
 * @param width the measured width of {@code file}. This is the number the whole table
 *     exists for: the cover minimum used to be checked against a figure the browser
 *     reported, which {@code SubmissionChecklist} says in its own header a client could
 *     make up
 * @param height likewise
 * @param blurDataUrl §13.1's sixteen-pixel sample as a data URL, so a placeholder arrives
 *     in the same response as the image and costs no extra request
 */
public record TranscodedImage(Path file, String contentType, int width, int height, String blurDataUrl) {

    public TranscodedImage {
        Objects.requireNonNull(file, "A transcode produces a file");
        Objects.requireNonNull(contentType, "A transcode decides a type");
        Objects.requireNonNull(blurDataUrl, "A transcode produces a placeholder");
        if (width <= 0 || height <= 0) {
            // Zero or negative extent is not a small image, it is a measurement that
            // failed -- the same argument CoverImage makes, and the reason it matters
            // more here is that this measurement is the one being trusted.
            throw new IllegalArgumentException("A transcoded image has positive dimensions");
        }
    }
}
