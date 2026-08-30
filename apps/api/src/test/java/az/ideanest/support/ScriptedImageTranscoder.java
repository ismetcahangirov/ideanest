package az.ideanest.support;

import az.ideanest.media.application.ImageTranscoder;
import az.ideanest.media.application.MediaFailedException;
import az.ideanest.media.application.TranscodedImage;
import az.ideanest.media.domain.MediaFailureReason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The transcoder, scripted — the media pipeline design of 2026-08-30.
 *
 * <h2>Why the integration suite does not run libvips</h2>
 *
 * <p>Because the conversion is tested where it belongs. {@code VipsImageTranscoderTests}
 * asserts on the bytes of a real conversion — the EXIF is gone, a 4000×3000 photograph comes
 * back 1440×1080, a transparent PNG stays PNG — against a real installation, and is guarded
 * so that a machine without one can still work on this repository.
 *
 * <p>What is left for the integration suite is everything the conversion is embedded in: the
 * sweep claiming a row, the raw object being replaced and then removed, the campaign refusing
 * an upload that belongs to somebody else, and the dimensions on a saved cover being the
 * server's measurement. None of that becomes better tested by spawning a process, and a
 * suite that required a native dependency to start would be one that does not run.
 */
public class ScriptedImageTranscoder implements ImageTranscoder {

    /** What a conversion reports unless a test says otherwise. */
    private int width = 1440;

    private int height = 810;

    private String contentType = "image/jpeg";

    private MediaFailureReason refusal;

    /** Makes the next conversion report this size. */
    public void willProduce(int width, int height) {
        this.width = width;
        this.height = height;
        this.refusal = null;
    }

    /** Makes the next conversion refuse, as a real one would for a file that is not an image. */
    public void willRefuse(MediaFailureReason reason) {
        this.refusal = reason;
    }

    /** Back to the defaults, between tests. */
    public void reset() {
        this.width = 1440;
        this.height = 810;
        this.contentType = "image/jpeg";
        this.refusal = null;
    }

    @Override
    public TranscodedImage transcode(Path source, Path workingDirectory) {
        if (refusal != null) {
            throw new MediaFailedException(refusal, "Scripted refusal");
        }
        Path derived = workingDirectory.resolve("derived.jpg");
        try {
            // Copied rather than invented, so that a test can assert the derived object is
            // the bytes that were uploaded and not an empty file the sweep happened to write.
            Files.copy(source, derived, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
        return new TranscodedImage(derived, contentType, width, height, "data:image/jpeg;base64,AAAA");
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
