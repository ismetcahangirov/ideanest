package az.ideanest.payment.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Where the next page of the payment log starts — issue #412.
 *
 * <h2>Why the identifier stopped being enough</h2>
 *
 * <p>The log used to be ordered by {@code id} alone, on the argument
 * {@code PaymentTransactionRepository} carried and {@code AuditCursor} describes: a UUID v7
 * holds the millisecond it was minted in (§7.3), it is unique where the timestamp is not, and
 * a unique sort key is a cursor of one value instead of two.
 *
 * <p>The argument has the same hole here that it had on the audit trail, and #404 found it
 * there first. The two columns are written by two different clocks. The identifier is minted
 * in the application when {@code PaymentTransaction} builds the row; {@code created_at} is
 * {@code DEFAULT now()} (V41) and is the database's, taken when the insert lands. A charge
 * that mints its key before a provider call and commits after it, two instances whose clocks
 * differ by a few milliseconds, and anything migrated in with a key from elsewhere all put the
 * two orders out of step — and {@code PaymentLogView} renders the timestamp while the query
 * ordered by the key.
 *
 * <h2>Why it mattered more here than it did on the trail</h2>
 *
 * <p>On the audit trail the cost was an investigator scrolling. Here the rows are retry
 * attempts against somebody's card and <strong>the order is the evidence</strong>: §9.6 permits
 * four collection attempts, and "declined, declined, collected" read in the wrong order is a
 * different story about the same pledge. Within one pledge {@code attemptNumber} disambiguates
 * them, so the damage was bounded — but the unfiltered log, which is what the screen opens on,
 * has nothing to fall back on.
 *
 * <h2>Its own type, and not {@code AuditCursor}</h2>
 *
 * <p>The two are the same shape and are deliberately not the same class, which is the call
 * {@code AuditCursor} already makes about {@code ProfileCursor} and {@code SignalCursor}: a
 * shared cursor makes two features unable to change their ordering without each other's
 * consent, and a payment log and an audit trail are read by different people for different
 * reasons. Sharing it would also let a cursor from one endpoint be handed to the other and
 * decode cleanly, which is a page of somebody else's rows rather than a refusal.
 *
 * @param at {@code created_at} of the row at the bottom of the page just served
 * @param id the identifier of that same row, which breaks the tie when two rows share the
 *     instant. §9.6's four attempts inside one second make the tie the ordinary case here
 *     rather than the edge one, which is the whole reason this is a pair
 */
public record PaymentLogCursor(Instant at, UUID id) {

    private static final String SEPARATOR = ":";

    public PaymentLogCursor {
        if (at == null || id == null) {
            throw new IllegalArgumentException("A cursor is an instant and an identifier");
        }
    }

    /**
     * The opaque form a client is handed and hands back.
     *
     * <p>Opaque on purpose, and base64 is not the reason — anybody can decode it. What the
     * encoding buys is that no client is tempted to construct one, because the moment a client
     * builds cursors the ordering columns become part of the API contract and can no longer be
     * changed. Which is precisely what happened here: the previous cursor was a bare
     * identifier, {@code openapi.json} documented it as a {@code uuid}, and it read as a
     * promise that the identifier was the order.
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
     * @throws InvalidPaymentLogCursorException when the value is not one this endpoint
     *     produced. Refused rather than ignored: quietly serving the first page for a corrupt
     *     cursor would make a client that is paging wrongly look like one that has reached the
     *     end — and on this surface that means an operator reconciling a collection run
     *     reading the top of the log again in place of the attempts they had not seen
     */
    public static PaymentLogCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(SEPARATOR);
            if (separator < 0) {
                throw new InvalidPaymentLogCursorException();
            }
            return new PaymentLogCursor(
                    Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            // Everything a hand-written cursor can fail at: base64 that is not base64, an
            // identifier that is not a UUID, and the constructor's own refusal. One answer for
            // all of them, because the client's next move is the same in every case -- start
            // the list again -- and naming which half was wrong would be telling whoever is
            // probing how the value is built.
            throw new InvalidPaymentLogCursorException();
        }
    }
}
