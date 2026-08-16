package az.ideanest.discovery.application;

import az.ideanest.discovery.domain.CollectionKind;
import az.ideanest.discovery.domain.ProjectCard;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything about a collection that a curator decides, in one value.
 *
 * <p><strong>The whole description, not a patch.</strong> {@code PUT} rather than
 * {@code PATCH} on the admin endpoint, and this is the shape that goes with it: an
 * absent window is a collection with no window rather than a window left alone, and
 * an absent locale is a translation that has been removed. A partial update would
 * need {@code Patched} for every nullable field to tell "not sent" from "cleared",
 * which is machinery worth building for the campaign editor — where a client
 * autosaves one field at a time — and not for an admin form that submits itself
 * whole.
 *
 * <p>The slug is not here. It is the URL handle and D-08's landing page address, and
 * V14 calls it stable; renaming one breaks every link to it, so it is set at creation
 * and never edited.
 *
 * @param kind which of §4.3's three kinds of list this is
 * @param opensAt null for a standing list
 * @param closesAt null for one that does not expire
 * @param grantsBadge whether membership badges a campaign (§3.2). The single most
 *     consequential field here, which is why it is not implied by {@code kind}
 * @param sortOrder placement in the collections index
 * @param coverImage null while there is none
 * @param copy one entry per locale, keyed by the codes of §21.1. Must contain
 *     {@code az}; see {@link CurationService}
 */
public record CollectionDraft(
        CollectionKind kind,
        Instant opensAt,
        Instant closesAt,
        boolean grantsBadge,
        int sortOrder,
        ProjectCard.CoverImage coverImage,
        Map<String, Copy> copy) {

    public CollectionDraft {
        // Ordered and immutable, so that two writes of the same form produce the same
        // rows in the same order and the admin read is stable.
        copy = copy == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    /**
     * A collection's reader-facing copy in one language.
     *
     * @param title the heading of D-08's landing page, and the label of the programme
     *     facet. Required in every locale a row exists for
     * @param description the standfirst under it, or null
     */
    public record Copy(String title, String description) {
    }
}
