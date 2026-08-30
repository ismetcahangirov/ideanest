package az.ideanest.media.application;

import az.ideanest.media.MediaProperties;
import az.ideanest.media.domain.MediaAsset;
import az.ideanest.media.domain.MediaFailureReason;
import az.ideanest.media.domain.MediaStatus;
import az.ideanest.media.infrastructure.MediaAssetRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uploads, from the address being issued to the image being servable — the media pipeline
 * design of 2026-08-30.
 *
 * <h2>This module's whole surface to everything else</h2>
 *
 * <p>{@code ModuleBoundaryTests} lets another module reach an application layer and refuses
 * it a {@code domain} or an {@code infrastructure} package, so {@link #viewsOf} and
 * {@link #claimForOwner} are what the project module sees. Neither hands out a
 * {@link MediaAsset}: the entity is a state machine with transitions on it, and a caller
 * holding one could move it.
 */
@Service
public class MediaLibrary {

    /** Where an upload lands before anything has looked at it. Replaced, then deleted. */
    private static final String RAW_PREFIX = "uploads/";

    /** Where the derived image lives. This is the half a browser fetches. */
    private static final String DERIVED_PREFIX = "media/";

    private final MediaAssetRepository assets;
    private final ObjectStore store;
    private final MediaProperties properties;
    private final Clock clock;

    public MediaLibrary(MediaAssetRepository assets, ObjectStore store, MediaProperties properties, Clock clock) {
        this.assets = assets;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Issues an address the browser may upload one file to.
     *
     * <p>The declared type and size are the client's word and are treated as such: they are
     * checked here so that an obviously wrong request is refused before an address is issued,
     * and checked again from the bytes once they arrive, because a presigned address does not
     * make a declaration binding.
     *
     * @throws UploadsUnavailableException when this deployment has no storage configured
     * @throws MediaFailedException when the declared size is over the ceiling
     */
    @Transactional
    public MediaUpload begin(UUID ownerUserId, String declaredContentType, long declaredBytes) {
        if (!store.isAvailable()) {
            throw new UploadsUnavailableException("This deployment has no media storage configured.");
        }
        if (declaredBytes > properties.maxUploadBytes()) {
            throw new MediaFailedException(
                    MediaFailureReason.TOO_LARGE,
                    "That file is larger than the %d MB this platform accepts."
                            .formatted(properties.maxUploadBytes() / (1024 * 1024)));
        }
        if (declaredBytes <= 0) {
            throw new MediaFailedException(MediaFailureReason.EMPTY, "That file is empty.");
        }

        Instant now = clock.instant();
        MediaAsset asset = assets.save(MediaAsset.awaitingUpload(ownerUserId, now));

        String signedType = normalisedType(declaredContentType);
        URI address = store.presignedPut(rawKeyOf(asset.getId()), signedType, properties.uploadWindow());

        return new MediaUpload(
                asset.getId(), address, signedType, now.plus(properties.uploadWindow()), properties.maxUploadBytes());
    }

    /**
     * The client says the bytes are there.
     *
     * <p><strong>Idempotent, and a replay is the ordinary case.</strong> A browser whose
     * connection dropped between the upload finishing and this response arriving will send it
     * again; a second enqueue would have the sweep read an object the first pass may already
     * have replaced. {@code MediaAsset#markUploaded} refuses from any state but
     * {@code PENDING} and this simply returns what the row says now.
     */
    @Transactional
    public MediaAsset complete(UUID ownerUserId, UUID mediaId) {
        MediaAsset asset = ownedOrThrow(ownerUserId, mediaId);
        asset.markUploaded(clock.instant());
        return assets.save(asset);
    }

    /** One upload's state, for the editor's poll. Scoped to its owner. */
    @Transactional(readOnly = true)
    public MediaAsset statusOf(UUID ownerUserId, UUID mediaId) {
        return ownedOrThrow(ownerUserId, mediaId);
    }

    /**
     * Confirms that these identifiers are this person's and are servable, for a module about
     * to attach one to something it owns.
     *
     * <p>Answers only the ones that pass. The caller decides what a missing identifier means
     * — the project module refuses the patch — and this deliberately does not throw, because
     * a form saving a cover and eight story images should be told which one is the problem
     * rather than that something was.
     */
    @Transactional(readOnly = true)
    public Set<UUID> claimForOwner(UUID ownerUserId, Collection<UUID> mediaIds) {
        if (mediaIds.isEmpty()) {
            return Set.of();
        }
        return assets.findByIdInAndOwnerUserId(mediaIds, ownerUserId).stream()
                .filter(asset -> asset.getStatus() == MediaStatus.READY)
                .map(MediaAsset::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * What a renderer needs, for identifiers that are ready.
     *
     * <p>No owner, because a cover on a live campaign is public. Rows that are not
     * {@link MediaStatus#READY} are simply absent from the map: a caller rendering a campaign
     * whose cover is still processing shows what it showed before there was an image, which
     * is the same thing it shows for a campaign that never had one.
     */
    @Transactional(readOnly = true)
    public Map<UUID, MediaView> viewsOf(Collection<UUID> mediaIds) {
        if (mediaIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MediaView> views = new LinkedHashMap<>();
        for (MediaAsset asset : assets.findByIdIn(mediaIds)) {
            servableView(asset).ifPresent(view -> views.put(asset.getId(), view));
        }
        return Map.copyOf(views);
    }

    /** One, for the same purpose. */
    @Transactional(readOnly = true)
    public Optional<MediaView> viewOf(UUID mediaId) {
        return assets.findById(mediaId).flatMap(this::servableView);
    }

    private Optional<MediaView> servableView(MediaAsset asset) {
        if (asset.getStatus() != MediaStatus.READY) {
            return Optional.empty();
        }
        // Every one of these is present on a READY row, which V61's
        // media_ready_is_servable enforces rather than hopes for.
        return asset.getStorageKey()
                .map(key -> new MediaView(
                        asset.getId(),
                        store.publicUrl(key),
                        asset.getWidth().orElseThrow(),
                        asset.getHeight().orElseThrow(),
                        asset.getBlurDataUrl().orElseThrow()));
    }

    private MediaAsset ownedOrThrow(UUID ownerUserId, UUID mediaId) {
        return assets.findByIdAndOwnerUserId(mediaId, ownerUserId)
                .orElseThrow(() -> new MediaNotFoundException(mediaId));
    }

    /** The key the browser writes to. */
    public static String rawKeyOf(UUID mediaId) {
        return RAW_PREFIX + mediaId;
    }

    /** The key the derived image is served from, extension included so a CDN guesses right. */
    public static String derivedKeyOf(UUID mediaId, String contentType) {
        return DERIVED_PREFIX + mediaId + ("image/png".equals(contentType) ? ".png" : ".jpg");
    }

    /**
     * What the presigned address is signed for.
     *
     * <p>Signing for the type the client declared, and then deciding the real type from the
     * bytes, is not a contradiction: the signature binds what the browser may send so that a
     * leaked address cannot be used to upload something else, and the magic-byte check is what
     * decides whether we keep it. Anything unrecognised is signed as
     * {@code application/octet-stream} rather than refused here, because the honest refusal
     * happens where the content is.
     */
    private static String normalisedType(String declared) {
        if (declared == null || declared.isBlank() || !declared.startsWith("image/")) {
            return "application/octet-stream";
        }
        return declared.trim();
    }

    /**
     * What {@link #begin} tells the browser.
     *
     * @param contentType <strong>the type the address was signed for</strong>, which the
     *     client must send verbatim on the {@code PUT}. Returned rather than left for the
     *     client to reproduce because {@link #normalisedType} rewrites anything that is not
     *     an image type, and a client that sent its own value instead would have every
     *     upload refused by the store as a signature mismatch — a failure that looks like a
     *     credentials problem and is not
     */
    public record MediaUpload(UUID mediaId, URI uploadUrl, String contentType, Instant expiresAt, long maxBytes) {}

    /**
     * A servable image, as everything outside this module sees it.
     *
     * @param id the media identifier, so a caller can re-read the state later
     * @param url what a browser fetches
     * @param width the measured width. The number this whole table exists to make honest
     * @param height likewise
     * @param blurDataUrl §13.1's placeholder, in the same response as the image
     */
    public record MediaView(UUID id, String url, int width, int height, String blurDataUrl) {}
}
