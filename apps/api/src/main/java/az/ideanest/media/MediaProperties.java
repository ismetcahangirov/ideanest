package az.ideanest.media;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §13.1's ingestion envelope: where uploads go, what may be uploaded, and how large the
 * result is allowed to be — the media pipeline design of 2026-08-30.
 *
 * <h2>An unconfigured deployment starts, and refuses to accept an upload</h2>
 *
 * <p>The same position {@code VerificationProperties} takes about its keys, for the same
 * reason. There is no default bucket and no default endpoint, because either would be a
 * guess about somebody else's infrastructure — and a service that quietly wrote a
 * creator's photograph to a plausible-looking address would be worse than one that did
 * nothing.
 *
 * <p>So a deployment that configures nothing starts normally, serves every other endpoint,
 * and answers an upload request with a 503 saying that uploads are not configured here.
 * Refusing to start would trade one unavailable endpoint for a hundred.
 *
 * <h2>Why the ceiling is not the limit this work removed</h2>
 *
 * <p>{@code maxUploadBytes} is a denial-of-service control, not a rule about what makes a
 * good cover. Nobody is stopped from making a campaign by it — the design that introduced
 * this class exists to stop creators being blocked on image dimensions — and without it a
 * single request can occupy a processing slot with an arbitrarily large file.
 *
 * @param storage where the objects live. Absent means uploads are unavailable
 * @param maxUploadBytes the ceiling on one upload. §13.1's twenty megabytes
 * @param uploadWindow how long a presigned upload address stays valid. Short, because it is
 *     a credential: anybody holding the URL may write that one object until it expires
 * @param longestEdge what an image is reduced to. §13.1's {@code hero}, and the widest box
 *     in the product at 2× — see the design document on why nothing larger is stored
 * @param jpegQuality the quality of a re-encoded photograph, 1–100
 * @param processing the sweep that turns an uploaded object into a servable one
 */
@ConfigurationProperties(prefix = "ideanest.media")
public record MediaProperties(
        Storage storage,
        long maxUploadBytes,
        Duration uploadWindow,
        int longestEdge,
        int jpegQuality,
        Processing processing) {

    /** §13.1's "20MB images". */
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 20L * 1024 * 1024;

    private static final Duration DEFAULT_UPLOAD_WINDOW = Duration.ofMinutes(10);

    /**
     * §13.1's {@code hero}, and the same 1440 {@code next.config.mjs} stops its
     * {@code deviceSizes} at. The widest box in the product is 720 CSS px.
     */
    private static final int DEFAULT_LONGEST_EDGE = 1440;

    /**
     * Eighty-two. High enough that the artefacts are not visible on a photograph at the size
     * it is displayed, low enough that the file is a fraction of a lossless one — and the
     * stored object is an input to {@code next/image}, which re-encodes to AVIF or WebP
     * before a browser ever sees it, so this number decides storage rather than what is
     * delivered.
     */
    private static final int DEFAULT_JPEG_QUALITY = 82;

    public MediaProperties {
        maxUploadBytes = maxUploadBytes == 0 ? DEFAULT_MAX_UPLOAD_BYTES : maxUploadBytes;
        uploadWindow = uploadWindow == null ? DEFAULT_UPLOAD_WINDOW : uploadWindow;
        longestEdge = longestEdge == 0 ? DEFAULT_LONGEST_EDGE : longestEdge;
        jpegQuality = jpegQuality == 0 ? DEFAULT_JPEG_QUALITY : jpegQuality;
        processing = processing == null ? Processing.defaults() : processing;

        if (maxUploadBytes <= 0) {
            throw new IllegalArgumentException("An upload ceiling is a positive number of bytes");
        }
        if (!uploadWindow.isPositive()) {
            throw new IllegalArgumentException("An upload address is valid for some length of time");
        }
        if (longestEdge < 16) {
            throw new IllegalArgumentException("An image is reduced to something larger than its own placeholder");
        }
        if (jpegQuality < 1 || jpegQuality > 100) {
            throw new IllegalArgumentException("JPEG quality is between 1 and 100");
        }
    }

    /** Whether this deployment can accept an upload at all. */
    public boolean uploadsAvailable() {
        return storage != null && storage.isConfigured();
    }

    /**
     * The object store.
     *
     * <p>S3-compatible rather than one vendor's SDK. R2, MinIO and S3 itself all speak it,
     * and the choice of which is a deployment's rather than this repository's — which
     * matters here more than usual, because {@code deploy.yml} rolls out a digest through a
     * hook and this repository owns no infrastructure to make the choice on.
     *
     * @param endpoint the service address. Absent for AWS itself, where the region decides it
     * @param region the region name. Required by the SDK's signer even where it means nothing
     * @param bucket the bucket. Absent means uploads are unavailable
     * @param accessKeyId credential half. Absent falls back to the SDK's default provider
     *     chain, which is how an instance role or a mounted token is used instead
     * @param secretAccessKey the other half
     * @param publicBaseUrl what a stored key is served from — the CDN or bucket origin a
     *     browser fetches. Separate from {@code endpoint} because the address the service
     *     writes to and the address the world reads from are routinely not the same one, and
     *     conflating them is how a private endpoint ends up in a page
     * @param pathStyle whether keys go in the path rather than the host. MinIO needs it;
     *     most hosted services do not
     */
    public record Storage(
            String endpoint,
            String region,
            String bucket,
            String accessKeyId,
            String secretAccessKey,
            String publicBaseUrl,
            boolean pathStyle) {

        private static final String DEFAULT_REGION = "auto";

        public Storage {
            region = isBlank(region) ? DEFAULT_REGION : region;
            endpoint = blankAsNull(endpoint);
            bucket = blankAsNull(bucket);
            accessKeyId = blankAsNull(accessKeyId);
            secretAccessKey = blankAsNull(secretAccessKey);
            publicBaseUrl = trimTrailingSlash(blankAsNull(publicBaseUrl));
        }

        /**
         * A bucket and somewhere to serve it from.
         *
         * <p>Credentials are deliberately not required: the SDK's default provider chain
         * covers an instance role, which is the arrangement a deployment should prefer to
         * a key in an environment variable.
         */
        public boolean isConfigured() {
            return bucket != null && publicBaseUrl != null;
        }

        /** Whether a key and secret were given, as opposed to left to the provider chain. */
        public boolean hasStaticCredentials() {
            return accessKeyId != null && secretAccessKey != null;
        }

        private static String blankAsNull(String value) {
            return isBlank(value) ? null : value.trim();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static String trimTrailingSlash(String value) {
            if (value == null) {
                return null;
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
    }

    /**
     * The sweep.
     *
     * @param schedule when it fires, as {@code ScheduledJob} reads it. Every few seconds:
     *     a creator is watching a spinner, and this is the whole of the latency between
     *     their upload finishing and the image appearing
     * @param batchSize how many objects one pass processes. Bounded so that a backlog does
     *     not become one pass that overlaps its own next tick, and small because each item
     *     spawns a process
     * @param abandonedAfter how long an upload that never completed is kept before it is
     *     given up on. A {@code PENDING} row is somebody who closed the tab
     */
    public record Processing(String schedule, int batchSize, Duration abandonedAfter) {

        /**
         * Every five seconds.
         *
         * <p>Unlike every other schedule in this service, this one is not a sweep over rows
         * that can wait — it is a person watching a spinner. The lease still means one
         * replica does the work, and {@code batchSize} still bounds what a tick may take on.
         */
        private static final String DEFAULT_SCHEDULE = "*/5 * * * * *";

        private static final int DEFAULT_BATCH_SIZE = 4;

        private static final Duration DEFAULT_ABANDONED_AFTER = Duration.ofHours(6);

        static Processing defaults() {
            return new Processing(DEFAULT_SCHEDULE, DEFAULT_BATCH_SIZE, DEFAULT_ABANDONED_AFTER);
        }

        public Processing {
            schedule = schedule == null || schedule.isBlank() ? DEFAULT_SCHEDULE : schedule.trim();
            batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
            abandonedAfter = abandonedAfter == null ? DEFAULT_ABANDONED_AFTER : abandonedAfter;

            if (batchSize < 1) {
                throw new IllegalArgumentException("A pass processes at least one object");
            }
            if (!abandonedAfter.isPositive()) {
                throw new IllegalArgumentException("An abandoned upload is given up on after some time");
            }
        }
    }
}
