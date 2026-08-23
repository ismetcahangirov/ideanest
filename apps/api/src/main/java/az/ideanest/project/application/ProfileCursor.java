package az.ideanest.project.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Where the next page of a creator's campaigns starts — §4.2's profile, created tab.
 *
 * <p><strong>Keyset and not an offset</strong>, which §10.3 asks for and which this list
 * has a specific reason to want. A creator's page is ordered newest first, and a creator
 * who starts a draft between two of a reader's page requests shifts every row down by one
 * — so an offset would show the reader a campaign twice and skip nothing they had not seen
 * already, or, on the other side of the boundary, skip one entirely. A cursor names a row
 * rather than a position, so a campaign appearing above it changes nothing below it.
 *
 * <p><strong>Both halves, because one is not enough.</strong> Two campaigns created in the
 * same instant — a creator duplicating one, an import — would make a cursor on the
 * timestamp alone either repeat a row or drop one, depending on which side of the page
 * boundary the tie fell. The identifier breaks it, and the query orders by the same pair
 * so the comparison is a single row-value predicate rather than a special case.
 *
 * <p><strong>{@code created_at} rather than {@code launched_at}.</strong> The obvious
 * ordering key for a public list of campaigns is when each went live, and it cannot be
 * used: {@code launched_at} is null for {@code PRELAUNCH}, which is one of the nine states
 * this list serves, and a null sort key is a row that lands wherever the planner leaves it
 * and a cursor that cannot address it. {@code created_at} is {@code NOT NULL DEFAULT now()}
 * on every row.
 *
 * <p><strong>Its own type rather than {@code community.application.SignalCursor}.</strong>
 * That record is structurally identical and is another module's application layer, so
 * naming it would be legal and still wrong: it is documented as the cursor for a backer's
 * own saves and follows, and a shared cursor makes two features unable to change their
 * ordering without each other's agreement. The duplication is twenty lines and is the
 * cheaper side of that trade — the same conclusion {@code BackedPledgeFacts} reaches about
 * its copy of a state list, three lines from the original.
 *
 * @param at {@code created_at} of the row at the bottom of the page just served
 * @param id the identifier of that same row
 */
public record ProfileCursor(Instant at, UUID id) {

    private static final String SEPARATOR = ":";

    public ProfileCursor {
        if (at == null || id == null) {
            throw new IllegalArgumentException("A cursor is an instant and an identifier");
        }
    }

    /**
     * The opaque form a client is handed and hands back.
     *
     * <p><strong>Opaque on purpose, and base64 is not the reason.</strong> Anybody can
     * decode it; what the encoding buys is that no client is tempted to construct one,
     * because the moment a client builds cursors the ordering columns become part of the
     * API contract and can no longer be changed. {@code DiscoveryCursor} and
     * {@code SignalCursor} both make the same argument.
     */
    public String encode() {
        String plain = at + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A cursor a client handed back, or null when it handed back nothing.
     *
     * <p>The split is on the <em>last</em> separator rather than the first: an ISO-8601
     * instant contains colons of its own and a UUID contains none, so the last one is
     * always the boundary and the first one never is.
     *
     * @throws InvalidProfileCursorException when the value is not one this endpoint
     *     produced. Refused rather than ignored: quietly serving the first page for a
     *     corrupt cursor would make a client that is paging wrongly look like one that has
     *     reached the end, and the reader would see the top of the list again instead of
     *     the rest of it
     */
    public static ProfileCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(SEPARATOR);
            if (separator < 0) {
                throw new InvalidProfileCursorException();
            }
            return new ProfileCursor(
                    Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            // Everything a hand-written cursor can fail at: base64 that is not base64, an
            // identifier that is not a UUID, and the constructor's own refusal. One answer
            // for all of them, because the client's next move is the same in every case --
            // start the list again -- and naming which half was wrong would be telling
            // whoever is probing how the value is built.
            throw new InvalidProfileCursorException();
        }
    }
}
