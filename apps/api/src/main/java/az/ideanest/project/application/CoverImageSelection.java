package az.ideanest.project.application;

import java.util.UUID;

/**
 * What a creator chose as the cover, before it is resolved into a
 * {@link az.ideanest.project.domain.CoverImage} — the media pipeline design of 2026-08-30.
 *
 * <h2>Why the patch stopped carrying the domain type directly</h2>
 *
 * <p>Because the two ways of setting a cover no longer carry the same information. An
 * uploaded one is <em>an identifier</em>: the location and the dimensions are facts the
 * server already holds, measured when the file was processed, and a client that sent them
 * would be sending numbers we would then have to decide whether to believe. A typed one is
 * still all three values, because for that path there is nothing else.
 *
 * <p>Modelling both as the domain record meant either making its fields nullable — which is
 * the thing that record's constructor exists to refuse — or having the API layer invent
 * placeholder values for the service to overwrite. A sealed pair says what was actually
 * asked for, and {@code ProjectEditingService} is where it becomes a cover.
 */
public sealed interface CoverImageSelection {

    /**
     * An uploaded image, by identifier.
     *
     * <p>Resolved against the media module: it has to be this creator's, and it has to have
     * finished processing. Both are checked in the service rather than here, because both
     * are questions about rows.
     */
    record FromUpload(UUID mediaId) implements CoverImageSelection {}

    /**
     * A URL the creator typed, with the dimensions their browser measured.
     *
     * <p>Kept, and not deprecated. Every campaign that exists predates the uploader, the
     * editor still accepts a pasted address, and nothing about this path got worse — it is
     * simply the one where the numbers are the client's word. §5.3's size rule became advice
     * partly for that reason; see {@code ChecklistRequirement.COVER_IMAGE_SIZE}.
     */
    record FromUrl(String url, Integer width, Integer height) implements CoverImageSelection {}
}
