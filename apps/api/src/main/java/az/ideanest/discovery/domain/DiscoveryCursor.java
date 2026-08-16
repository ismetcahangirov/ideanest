package az.ideanest.discovery.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a page of results stopped, so the next one can start there.
 *
 * <p><strong>Keyset, not offset.</strong> An offset re-reads and discards every row
 * it skips, so the tenth page of a feed costs ten pages of work — but the reason it
 * is wrong here is correctness rather than cost. {@code projects.pledged_amount}
 * changes whenever anybody pledges, which on a funding platform is continuously. A
 * campaign that gains a pledge between page one and page two moves up the
 * {@code most_funded} order; every campaign below it shifts down by one; and
 * {@code OFFSET 24} then returns a row the client already has. One that loses its
 * place downwards makes {@code OFFSET 24} skip a row nobody will ever see. Both
 * failures are silent, and both are what a backer reports as "the list is broken".
 *
 * <p>A keyset cursor says "resume after this key and this id" rather than "skip
 * twenty-four rows", so a row that moves is seen once or not at all depending on
 * where it moved to, and no row that stayed still is ever duplicated or dropped.
 *
 * <p><strong>The key alone is not enough; the tiebreaker is part of the design.</strong>
 * Thousands of campaigns share a {@code pledged_amount} of zero and a
 * {@code backers_count} of zero. Resuming after a key that dozens of rows share
 * would skip all of them or repeat all of them, depending on which way the
 * comparison went. Every cursor therefore carries {@code id} as well, and every
 * order in {@link DiscoverySort} ends {@code , id ASC}, which makes it total.
 *
 * <p><strong>It is bound to the query that produced it.</strong> The fingerprint is
 * a digest of every filter and the sort. Replaying a cursor against a different
 * query is refused — see {@link InvalidCursorException}.
 *
 * <p><strong>Opaque, and not a security boundary.</strong> It is base64url over a
 * separated record and anybody can decode it. There is nothing privileged inside: a
 * sort key, an id that is already in the response body, and an instant. It is not
 * signed, because a forged cursor can only produce a page of campaigns the caller
 * could have asked for anyway, and an HMAC would add a key to rotate for no
 * confidentiality gained. It is opaque so that clients do not construct one by hand
 * and become dependent on the encoding — which is exactly what a dedicated search
 * engine (§11.1 tier 2) would change.
 *
 * @param fingerprint the query this cursor belongs to; see {@code DiscoveryQuery}
 * @param sort the order it was issued for, so a mismatch is caught even when two
 *     queries differ only in their sort
 * @param sortKey the last row's sort key, as text. <strong>Null means the last row's
 *     key was null</strong> — the nulls-last tail of {@code newest} or
 *     {@code ending_soon} — and is not the same as "no key", which cannot happen
 * @param id the last row's identifier, and the tiebreaker
 * @param asOf the instant {@link DiscoverySort#POPULARITY} decays against, pinned by
 *     the first page so that every later page scores the same campaigns the same way
 */
public record DiscoveryCursor(String fingerprint, DiscoverySort sort, String sortKey, UUID id, Instant asOf) {

    /**
     * The encoding version, first field of every cursor.
     *
     * <p>Present so that changing the layout is a decision rather than an accident.
     * A cursor from an older release decodes to a refusal and the client restarts,
     * which is the correct behaviour during the minutes a rolling deployment has
     * both versions serving.
     */
    private static final String VERSION = "1";

    /** ASCII unit separator: cannot occur in a hex digest, a uuid, or a number. */
    private static final char SEPARATOR = (char) 0x1f;

    private static final int FIELDS = 6;

    public DiscoveryCursor {
        Objects.requireNonNull(fingerprint, "A cursor names the query it belongs to");
        Objects.requireNonNull(sort, "A cursor names the order it was issued for");
        Objects.requireNonNull(id, "A cursor carries the tiebreaker");
        Objects.requireNonNull(asOf, "A cursor pins the instant it was issued at");
    }

    /** The opaque token, for {@code nextCursor}. */
    public String encode() {
        String payload = String.join(
                String.valueOf(SEPARATOR),
                VERSION,
                fingerprint,
                sort.wireValue(),
                // An empty field and a null key are distinguishable because the
                // presence flag is its own field: "0" is a null key, "1" is a key
                // that happens to be empty, which no sort produces but which the
                // encoding should not quietly conflate.
                sortKey == null ? "0" : "1",
                sortKey == null ? "" : sortKey,
                id.toString());
        String withInstant = payload + SEPARATOR + asOf.toString();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(withInstant.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The cursor a client sent back, or a refusal.
     *
     * <p>Does not check the fingerprint — the caller does, because only the caller
     * knows what the current query fingerprints to. Splitting the two keeps this
     * method a pure decoding step and lets the caller report the two failures
     * separately.
     *
     * @throws InvalidCursorException when the token is not a cursor this release wrote
     */
    public static DiscoveryCursor decode(String token) {
        if (token == null || token.isBlank()) {
            throw InvalidCursorException.undecodable("A cursor cannot be empty.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw InvalidCursorException.undecodable("That cursor is not a cursor this service issued.");
        }
        String payload = new String(decoded, StandardCharsets.UTF_8);
        // -1 so that a trailing empty field is kept: without it a cursor whose
        // instant somehow serialised empty would arrive with the wrong arity and
        // be reported as the wrong failure.
        String[] fields = payload.split(String.valueOf(SEPARATOR), -1);
        if (fields.length != FIELDS + 1) {
            throw InvalidCursorException.undecodable("That cursor is not a cursor this service issued.");
        }
        if (!VERSION.equals(fields[0])) {
            throw InvalidCursorException.undecodable(
                    "That cursor was issued by an earlier release. Start again from the first page.");
        }
        DiscoverySort sort = DiscoverySort.fromWireValue(fields[2])
                .orElseThrow(() -> InvalidCursorException.undecodable("That cursor names an order that does not exist."));
        boolean keyPresent = "1".equals(fields[3]);
        if (!keyPresent && !fields[4].isEmpty()) {
            throw InvalidCursorException.undecodable("That cursor is not a cursor this service issued.");
        }
        UUID id;
        try {
            id = UUID.fromString(fields[5]);
        } catch (IllegalArgumentException e) {
            throw InvalidCursorException.undecodable("That cursor is not a cursor this service issued.");
        }
        Instant asOf;
        try {
            asOf = Instant.parse(fields[6]);
        } catch (DateTimeParseException e) {
            throw InvalidCursorException.undecodable("That cursor is not a cursor this service issued.");
        }
        return new DiscoveryCursor(fields[1], sort, keyPresent ? fields[4] : null, id, asOf);
    }

    /**
     * Refuses a cursor that was minted for a different query or a different order.
     *
     * @throws InvalidCursorException when it does not match
     */
    public void requireMatches(String currentFingerprint, DiscoverySort currentSort) {
        if (!fingerprint.equals(currentFingerprint) || sort != currentSort) {
            throw InvalidCursorException.mismatched();
        }
    }
}
