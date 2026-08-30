package az.ideanest.media.infrastructure;

import az.ideanest.media.MediaProperties;
import az.ideanest.media.application.ImageTranscoder;
import az.ideanest.media.application.MediaFailedException;
import az.ideanest.media.application.TranscodedImage;
import az.ideanest.media.application.TranscoderUnavailableException;
import az.ideanest.media.domain.MediaAsset;
import az.ideanest.media.domain.MediaFailureReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The transcoder, over libvips — the media pipeline design of 2026-08-30.
 *
 * <h2>Why a native dependency is worth forty megabytes of runtime image</h2>
 *
 * <p>The JDK's own {@code ImageIO} reads and writes JPEG and PNG with no new dependency at
 * all, and it was the first choice. Two facts ruled it out.
 *
 * <p><strong>It cannot read HEIC</strong>, which is the default camera format on an iPhone.
 * iOS Safari usually transcodes to JPEG when a file input is used, and "usually" is not a
 * guarantee to build a first-run experience on — least of all in a change whose entire
 * purpose is that creators stop getting stuck at the first screen.
 *
 * <p><strong>It decodes to a full bitmap in heap.</strong> An 8000×6000 photograph is 192 MB
 * as a {@code BufferedImage}. The container sizes its heap with {@code MaxRAMPercentage}; two
 * or three concurrent uploads is an {@code OutOfMemoryError} rather than a slowdown. libvips
 * streams and never materialises the whole image, which is also what lets the sweep take a
 * batch of four without that number being a nervous one.
 *
 * <h2>Four processes per image, and what each is for</h2>
 *
 * <ol>
 *   <li>{@code vipsheader} on the input — decides whether this is an image at all, how big
 *       it is, and whether it has an alpha channel
 *   <li>{@code vips thumbnail} to the derived file
 *   <li>{@code vipsheader} on the output — the measurement the whole table exists for
 *   <li>{@code vips thumbnail} to sixteen pixels, for §13.1's placeholder
 * </ol>
 *
 * <p>{@code strip} on every save is what removes EXIF. §13.1 calls GPS coordinates in an
 * uploaded photograph a privacy leak rather than a detail, so the test for it asserts on the
 * output bytes rather than trusting this sentence.
 */
public class VipsImageTranscoder implements ImageTranscoder {

    private static final Logger log = LoggerFactory.getLogger(VipsImageTranscoder.class);

    /**
     * Generous, and bounded.
     *
     * <p>A large photograph is a second or two. Thirty is far enough beyond that to be
     * indistinguishable from success, and close enough that a process which has wedged does
     * not hold the sweep until somebody notices.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** §13.1's sixteen-pixel sample. */
    private static final int PLACEHOLDER_EDGE = 16;

    private final MediaProperties properties;
    private final boolean available;

    public VipsImageTranscoder(MediaProperties properties) {
        this.properties = Objects.requireNonNull(properties, "A transcoder needs its settings");
        this.available = probe();
    }

    /**
     * Whether libvips is installed here.
     *
     * <p>Asked once, at construction. A runtime image built without it is an operational
     * fault that should be visible in the start-up log rather than discovered by the first
     * creator to upload something.
     */
    private static boolean probe() {
        try {
            CommandResult result = run(List.of("vips", "--version"), null);
            if (result.succeeded()) {
                log.info("libvips available: {}", result.output().strip());
                return true;
            }
        } catch (TranscoderUnavailableException absent) {
            log.warn("libvips is not installed; uploaded images cannot be processed on this host");
            return false;
        }
        log.warn("libvips did not answer --version; uploaded images cannot be processed on this host");
        return false;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public TranscodedImage transcode(Path source, Path workingDirectory) {
        if (!available) {
            throw new TranscoderUnavailableException("libvips is not installed on this host");
        }

        SourceHeader header = readHeader(source);

        if (Math.min(header.width(), header.height()) < MediaAsset.MINIMUM_EDGE) {
            throw new MediaFailedException(
                    MediaFailureReason.TOO_SMALL,
                    "That image is %dx%d, which is smaller than anything this platform can display."
                            .formatted(header.width(), header.height()));
        }

        boolean alpha = header.hasAlpha();
        String contentType = alpha ? "image/png" : "image/jpeg";
        Path derived = workingDirectory.resolve(alpha ? "derived.png" : "derived.jpg");

        shrink(source, derived, saveOptionsFor(alpha), properties.longestEdge());

        SourceHeader derivedHeader = readHeader(derived);

        return new TranscodedImage(
                derived,
                contentType,
                derivedHeader.width(),
                derivedHeader.height(),
                placeholder(source, workingDirectory, alpha));
    }

    /**
     * The save options, and both of them matter.
     *
     * <p>{@code strip} removes every metadata block, EXIF included. {@code Q} is the quality
     * of a re-encoded photograph — see {@code MediaProperties} on why this number decides
     * storage rather than what a browser is served, {@code next/image} re-encoding to AVIF or
     * WebP before anybody sees it.
     *
     * <p>PNG for a source with an alpha channel, because JPEG has none and would composite
     * transparency onto black. A screenshot with a transparent corner is not a rare upload.
     */
    private String saveOptionsFor(boolean alpha) {
        return alpha ? "[strip]" : "[Q=%d,strip,optimize-coding]".formatted(properties.jpegQuality());
    }

    /**
     * §13.1's placeholder.
     *
     * <p>Always JPEG, even for an image kept as PNG: sixteen pixels of a photograph is under
     * a kilobyte as JPEG and several times that as PNG, and this string is carried in every
     * response the image appears in. Transparency at sixteen pixels is not information.
     */
    private String placeholder(Path source, Path workingDirectory, boolean alpha) {
        Path sample = workingDirectory.resolve("placeholder.jpg");
        shrink(source, sample, "[Q=50,strip]", PLACEHOLDER_EDGE);
        try {
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(sample));
            if (alpha) {
                log.debug("Placeholder for a transparent image is flattened to JPEG");
            }
            return "data:image/jpeg;base64," + base64;
        } catch (IOException problem) {
            throw new TranscoderUnavailableException("Could not read the placeholder that was just written", problem);
        }
    }

    /**
     * One {@code vips thumbnail}.
     *
     * <p>Both a width and a height, so the box bounds the longest edge whichever way the
     * photograph is turned — {@code thumbnail} fits inside the box and keeps the aspect ratio.
     *
     * <p>{@code --size down} is the half that matters most here: it shrinks and never
     * enlarges. An image that arrives smaller than the box is stored at the size it came in,
     * because upscaling invents detail and this pipeline exists to stop small images being
     * refused, not to pretend they are large ones.
     */
    private static void shrink(Path source, Path destination, String saveOptions, int edge) {
        CommandResult result = run(
                List.of(
                        "vips",
                        "thumbnail",
                        source.toString(),
                        destination + saveOptions,
                        String.valueOf(edge),
                        "--height",
                        String.valueOf(edge),
                        "--size",
                        "down"),
                source.getParent());

        if (!result.succeeded()) {
            throw new MediaFailedException(
                    MediaFailureReason.UNREADABLE,
                    "That image could not be converted: " + result.firstLineOfError());
        }
    }

    /** What {@code vipsheader} says about a file, and whether it is an image at all. */
    private static SourceHeader readHeader(Path file) {
        CommandResult result = run(List.of("vipsheader", "-a", file.toString()), file.getParent());
        if (!result.succeeded()) {
            /*
             * vipsheader failing is the whole of the format check. The bytes were not
             * something any of libvips' loaders recognised -- which covers a PDF renamed
             * to .jpg, a truncated file, and a format nobody has built support for -- and
             * the creator's next move is the same in every case.
             */
            throw new MediaFailedException(
                    MediaFailureReason.UNSUPPORTED_FORMAT, "That file is not an image this platform can read.");
        }
        return SourceHeader.parse(result.output());
    }

    /**
     * {@code vipsheader -a}, as the two numbers and the one question this needs.
     *
     * @param width in pixels
     * @param height in pixels
     * @param bands how many channels. Two means grey with alpha, four means colour with
     *     alpha; one and three are the same without it
     */
    private record SourceHeader(int width, int height, int bands) {

        boolean hasAlpha() {
            return bands == 2 || bands >= 4;
        }

        static SourceHeader parse(String output) {
            int width = fieldOf(output, "width");
            int height = fieldOf(output, "height");
            int bands = fieldOf(output, "bands");

            if (width <= 0 || height <= 0) {
                // A header that parsed and reports no extent is a file vips read and this
                // code did not understand. Refusing is right; guessing would put a zero in
                // a column the schema refuses anyway.
                throw new MediaFailedException(
                        MediaFailureReason.UNREADABLE, "That image reports no dimensions.");
            }
            return new SourceHeader(width, height, bands <= 0 ? 3 : bands);
        }

        private static int fieldOf(String output, String name) {
            for (String line : output.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.startsWith(name + ":")) {
                    try {
                        return Integer.parseInt(trimmed.substring(name.length() + 1).strip());
                    } catch (NumberFormatException notANumber) {
                        return -1;
                    }
                }
            }
            return -1;
        }
    }

    /**
     * Runs one command and waits, bounded.
     *
     * <p><strong>Output goes to a file rather than through a pipe</strong>, and both halves
     * of that are deliberate. Reading a pipe from this thread and then calling
     * {@code waitFor} with a timeout gives a timeout that cannot fire: the read blocks until
     * the process exits, so a wedged process wedges the sweep with it. Reading it after
     * {@code waitFor} instead deadlocks the process the moment the pipe fills, which for one
     * writing a page of libvips internals is not a rare case.
     *
     * <p>A file has neither problem and costs a few kilobytes in a directory that is deleted
     * at the end of the item. {@code redirectErrorStream} puts the error in it too, because
     * what is wanted on a failure is whatever the process said, in the order it said it.
     */
    private static CommandResult run(List<String> command, Path workingDirectory) {
        Path directory = workingDirectory == null ? Path.of(System.getProperty("java.io.tmpdir")) : workingDirectory;

        Process process = null;
        Path transcript = null;
        try {
            transcript = Files.createTempFile(directory, "vips-", ".log");

            process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(transcript.toFile())
                    .start();

            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new TranscoderUnavailableException("A conversion did not finish within " + TIMEOUT);
            }
            String output = Files.readString(transcript, StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), output);

        } catch (IOException notRunnable) {
            // The binary is not there, or cannot be executed. Not the creator's problem.
            throw new TranscoderUnavailableException("Could not run " + command.get(0), notRunnable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new TranscoderUnavailableException("Interrupted while converting", interrupted);
        } finally {
            deleteQuietly(transcript);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The working directory is removed wholesale by the caller.
        }
    }

    /** An exit status and whatever the process said. */
    private record CommandResult(int exitCode, String output) {

        boolean succeeded() {
            return exitCode == 0;
        }

        /**
         * Enough of the error to be worth logging, and no more.
         *
         * <p>The whole of it can be a page of libvips internals, and this string reaches a
         * problem detail — which is a document a client may log.
         */
        String firstLineOfError() {
            String first = output.lines().findFirst().orElse("no output").strip();
            return first.length() > 200 ? first.substring(0, 200) : first;
        }
    }
}
