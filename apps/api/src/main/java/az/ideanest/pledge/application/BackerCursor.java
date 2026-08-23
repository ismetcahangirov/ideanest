package az.ideanest.pledge.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Where the next page of a backer's pledges starts — §4.2's P-04 archive, and §4.8's
 * pledge list.
 *
 * <p><strong>One type for both lists, because both lists are the same read.</strong>
 * {@code GET /v1/users/{slug}/backed} and {@code GET /v1/me/pledges} page over
 * {@code pledges} keyed on {@code backer_id}, ordered newest first and tie-broken on the
 * identifier; what differs between them is the state filter, whether anonymous rows are
 * dropped, and what is rendered — none of which the cursor sees. Two records would be the
 * same twenty lines twice and two places for the encoding to drift.
 * {@code SignalCursor} makes exactly this argument about saves and follows.
 *
 * <p><strong>{@code created_at} rather than {@code confirmed_at}.</strong> The archive's
 * natural key is when the pledge was made, and {@code confirmed_at} is null on a
 * {@link az.ideanest.pledge.domain.PledgeState#DRAFT} — which the backer's own list
 * carries. A null sort key is a row that lands wherever the planner leaves it and a cursor
 * that cannot address it. The archive's states all have a confirmation instant and could
 * have used it; using one key for both lists is what lets one cursor serve both.
 *
 * <p><strong>Both halves, because one is not enough.</strong> Two pledges created in the
 * same instant — a backer with two tabs, a client replaying a list — would make a cursor on
 * the timestamp alone either skip a row or repeat it, depending on which side of the page
 * boundary the tie fell. The identifier breaks it, and the query orders by the same pair so
 * the comparison stays a single row-value predicate.
 *
 * @param at {@code created_at} of the pledge at the bottom of the page just served
 * @param id the identifier of that same pledge
 */
public record BackerCursor(Instant at, UUID id) {

    private static final String SEPARATOR = ":";

    public BackerCursor {
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
     * API contract and can no longer be changed.
     */
    public String encode() {
        String plain = at + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A cursor a client handed back, or null when it handed back nothing.
     *
     * <p>The split is on the <em>last</em> separator rather than the first: an ISO-8601
     * instant contains colons of its own and a UUID contains none.
     *
     * @throws InvalidBackerCursorException when the value is not one these endpoints
     *     produced. Refused rather than ignored: quietly serving the first page would make
     *     a client that is paging wrongly look like one that has reached the end
     */
    public static BackerCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(SEPARATOR);
            if (separator < 0) {
                throw new InvalidBackerCursorException();
            }
            return new BackerCursor(
                    Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            // Everything a hand-written cursor can fail at, answered identically: the
            // client's next move is the same in every case -- start the list again -- and
            // naming which half was wrong would tell whoever is probing how it is built.
            throw new InvalidBackerCursorException();
        }
    }
}
