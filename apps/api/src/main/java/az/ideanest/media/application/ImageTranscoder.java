package az.ideanest.media.application;

import java.nio.file.Path;

/**
 * Turns whatever a creator uploaded into the one file this platform stores — the media
 * pipeline design of 2026-08-30.
 *
 * <h2>One derived file, not §13.1's four variants</h2>
 *
 * <p>§13.1 asks ingestion for 160w, 640w, 1440w and the original, in AVIF with WebP and
 * JPEG behind it. This produces <strong>one</strong>, and the reason is that the delivery
 * half of §13.1 is already built and already does the rest: {@code next/image}
 * content-negotiates AVIF then WebP, {@code deviceSizes} stops at 1440 and
 * {@code imageSizes} starts at 16, cached thirty days. Emitting four here would have the
 * optimiser derive its own variants from ours — the same encoding twice, and roughly five
 * times the storage for it.
 *
 * <p>Quality is not what is being traded. The widest box in the product is 720 CSS px, so
 * 1440 is that box at 2×; anything beyond it encodes pixels nobody can resolve, which is
 * the reasoning {@code next.config.mjs} already used to drop Next's 2048 and 3840
 * candidates.
 *
 * <h2>A port, because the implementation is a process</h2>
 *
 * <p>The implementation shells out to libvips. That is a native dependency in the runtime
 * image, and an interface here is what lets the tests that are about the pipeline's
 * <em>logic</em> run without it.
 */
public interface ImageTranscoder {

    /**
     * Reads {@code source}, writes the derived image beside it, and describes the result.
     *
     * <p>Implementations must strip metadata. A photograph from a phone carries GPS
     * coordinates, and §13.1 names that a privacy leak rather than a detail — which is why
     * the test for it asserts on the output rather than trusting this sentence.
     *
     * @param source the uploaded file, as it arrived
     * @param workingDirectory where the derived file and the placeholder may be written
     * @throws MediaFailedException when the file is not an image this pipeline reads, is
     *     too small to display, or defeats the decoder — all of which are the creator's to
     *     act on
     * @throws TranscoderUnavailableException when the transcoder itself is missing or
     *     cannot be run, which is not
     */
    TranscodedImage transcode(Path source, Path workingDirectory);

    /** Whether the transcoder can actually be run here. */
    boolean isAvailable();
}
