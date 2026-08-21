package az.ideanest.community.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Where the next page of a backer's own list starts — saved campaigns, or followed accounts.
 *
 * <p><strong>One type for both lists, because both lists are the same read.</strong> A save and
 * a follow are ordered by when they were made and tie-broken on the identifier, so a cursor for
 * one is structurally a cursor for the other. Two records would be the same twenty lines twice
 * and two places for the encoding to drift.
 *
 * <p><strong>Both halves, because one is not enough.</strong> Two campaigns saved in the same
 * instant — one tap after another, or a client replaying a list — would make a cursor on the
 * timestamp alone either skip a row or repeat it, depending on which side of the comparison the
 * page boundary fell. The identifier breaks the tie, and {@code saves_account_idx} and
 * {@code follows_follower_idx} are both ordered to match so the read stays a range scan.
 *
 * @param at when the row at the bottom of the page just served was created
 * @param id the identifier of that same row
 */
public record SignalCursor(Instant at, UUID id) {

    private static final String SEPARATOR = ":";

    public SignalCursor {
        if (at == null || id == null) {
            throw new IllegalArgumentException("A cursor is an instant and an identifier");
        }
    }

    /**
     * The opaque form a client is handed and hands back.
     *
     * <p><strong>Opaque on purpose, and base64 is not the reason.</strong> Anybody can decode
     * it; what the encoding buys is that no client is tempted to construct one, because the
     * moment a client builds cursors the ordering columns become part of the API contract and
     * can no longer be changed. The same argument {@code DiscoveryCursor} makes.
     */
    public String encode() {
        String plain = at + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A cursor a client handed back, or null when it handed back nothing.
     *
     * <p>The split is on the <em>last</em> separator rather than the first: an ISO-8601 instant
     * contains colons of its own and a UUID contains none, so the last one is always the
     * boundary and the first one never is.
     *
     * @throws InvalidSignalCursorException when the value is not one this endpoint produced.
     *     Refused rather than ignored: quietly serving the first page for a corrupt cursor
     *     would make a client that is paging wrongly look like one that has reached the end,
     *     and the reader would see the top of their list again instead of the rest of it
     */
    public static SignalCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(SEPARATOR);
            if (separator < 0) {
                throw new InvalidSignalCursorException();
            }
            return new SignalCursor(
                    Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            // Everything a hand-written cursor can fail at: base64 that is not base64, an
            // identifier that is not a UUID, and the constructor's own refusal. One answer for
            // all of them, because the client's next move is the same in every case -- start
            // the list again -- and naming which half was wrong would be telling whoever is
            // probing how the value is built.
            throw new InvalidSignalCursorException();
        }
    }
}
