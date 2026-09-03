package az.ideanest.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Where the next page of the trail starts — AD-14, and the correctness half of #404.
 *
 * <h2>Why the identifier stopped being enough</h2>
 *
 * <p>The trail used to be ordered by {@code id} alone, on the argument that a UUID v7
 * carries the millisecond it was minted in (§7.3), so the primary key and
 * {@code occurred_at} say the same thing and only one of them is unique. The argument has
 * a hole in it, and {@code /admin/audit} rendered the hole: the two columns are written by
 * two different clocks. The identifier is minted in the application when
 * {@code AuditEntry.record} builds the row; {@code occurred_at} is
 * {@code DEFAULT now()} and is the database's, taken when the insert lands. A transaction
 * that mints a row and commits it later, two application instances whose clocks differ,
 * and a row backdated by an import all put the two orders out of step — and the screen
 * displays the timestamp while the query ordered by the key.
 *
 * <p>The observed result was a page headed "newest first" whose first fourteen rows were
 * from last month and whose entries from that morning began at position fifteen. On an
 * audit surface that is not a rough edge: an investigator who opens the log and sees August
 * at the top has no reason to scroll for this morning, and the screen has told them
 * something false about the one table on the platform that exists to be trusted.
 *
 * <p><strong>So the sort key is now the column that is displayed</strong>, and this record
 * is what that costs: a cursor over a non-unique column has to carry the tiebreak with it.
 * {@code ProfileCursor} and {@code SignalCursor} reach the same shape from the same
 * problem, and each says why it is its own type rather than one of the others — a shared
 * cursor makes two features unable to change their ordering without each other's consent.
 *
 * <h2>What this did not cost</h2>
 *
 * <p>No migration. V21's four indexes all end in {@code occurred_at DESC}, which is exactly
 * the range this now scans; the identifier tiebreak is applied to the rows sharing a single
 * timestamp, which is a handful even in the case that motivated the fix. Adding {@code id}
 * to each index would make the keyset exact rather than nearly exact and would be an index
 * rebuild on the one table that only grows — worth doing on the day the tie is measurably
 * expensive, and not before.
 *
 * @param at {@code occurred_at} of the row at the bottom of the page just served
 * @param id the identifier of that same row, which breaks the tie when two rows share the
 *     instant. Two rows written by one transaction share it, so the tie is the normal case
 *     here rather than the edge one
 */
public record AuditCursor(Instant at, UUID id) {

    private static final String SEPARATOR = ":";

    public AuditCursor {
        if (at == null || id == null) {
            throw new IllegalArgumentException("A cursor is an instant and an identifier");
        }
    }

    /**
     * The opaque form a client is handed and hands back.
     *
     * <p>Opaque on purpose, and base64 is not the reason — anybody can decode it. What the
     * encoding buys is that no client is tempted to construct one, because the moment a
     * client builds cursors the ordering columns become part of the API contract and can no
     * longer be changed. Which is precisely what happened here: the previous cursor was a
     * bare identifier, and it read as a promise that the identifier was the order.
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
     * @throws InvalidAuditCursorException when the value is not one this endpoint produced.
     *     Refused rather than ignored: quietly serving the first page for a corrupt cursor
     *     would make a client that is paging wrongly look like one that has reached the end
     *     — and on this surface it would hand an investigator the top of the log again in
     *     place of the part they had not read
     */
    public static AuditCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(SEPARATOR);
            if (separator < 0) {
                throw new InvalidAuditCursorException();
            }
            return new AuditCursor(
                    Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            // Everything a hand-written cursor can fail at: base64 that is not base64, an
            // identifier that is not a UUID, and the constructor's own refusal. One answer
            // for all of them, because the client's next move is the same in every case --
            // start the list again -- and naming which half was wrong would be telling
            // whoever is probing how the value is built.
            throw new InvalidAuditCursorException();
        }
    }
}
