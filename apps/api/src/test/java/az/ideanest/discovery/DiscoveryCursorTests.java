package az.ideanest.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.discovery.application.DiscoveryQuery;
import az.ideanest.discovery.domain.DiscoveryCursor;
import az.ideanest.discovery.domain.DiscoverySort;
import az.ideanest.discovery.domain.DiscoveryStatus;
import az.ideanest.discovery.domain.InvalidCursorException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cursor, on its own.
 *
 * <p>A plain unit test: the encoding and the fingerprint are pure functions, and the
 * behaviour they produce over HTTP is checked separately in
 * {@code DiscoveryApiTests}. What is here is the part that has to hold before any of
 * that is meaningful — that a cursor survives a round trip exactly, and that one which
 * belongs to another query is refused rather than half-understood.
 */
class DiscoveryCursorTests {

    private static final UUID ID = UUID.fromString("0193f2a1-0000-7000-8000-000000000001");
    private static final Instant AS_OF = Instant.parse("2026-08-16T12:00:00Z");

    @Test
    @DisplayName("a cursor survives a round trip exactly")
    void encodingIsReversible() {
        DiscoveryCursor cursor =
                new DiscoveryCursor("abcdef0123456789", DiscoverySort.MOST_FUNDED, "1250.00", ID, AS_OF);

        assertThat(DiscoveryCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    @DisplayName("a null sort key survives as a null sort key")
    void theNullTailIsRepresentable() {
        // Not decoration. `newest` and `ending_soon` sort nulls last, so a scroll
        // that reaches the campaigns which never launched has a null key — and a
        // cursor that could not carry one would either restart the scroll or lose
        // every unlaunched campaign from the second page onwards.
        DiscoveryCursor cursor = new DiscoveryCursor("abcdef0123456789", DiscoverySort.NEWEST, null, ID, AS_OF);

        DiscoveryCursor decoded = DiscoveryCursor.decode(cursor.encode());
        assertThat(decoded.sortKey()).isNull();
        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    @DisplayName("the token is opaque and url-safe")
    void theTokenSurvivesAQueryString() {
        String token = new DiscoveryCursor("abcdef0123456789", DiscoverySort.NEWEST, AS_OF.toString(), ID, AS_OF)
                .encode();

        // No '+', '/' or '=' — a client that pasted one of those into a query string
        // unescaped would get a cursor back that is not the one it sent.
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("a cursor from another sort is refused")
    void aCursorFromAnotherSortIsRefused() {
        DiscoveryCursor cursor = new DiscoveryCursor("abcdef0123456789", DiscoverySort.NEWEST, null, ID, AS_OF);

        assertThatThrownBy(() -> cursor.requireMatches("abcdef0123456789", DiscoverySort.MOST_FUNDED))
                .isInstanceOfSatisfying(InvalidCursorException.class, e -> assertThat(e.isMismatch())
                        .isTrue());
    }

    @Test
    @DisplayName("a cursor from another filter set is refused")
    void aCursorFromAnotherFilterIsRefused() {
        DiscoveryCursor cursor = new DiscoveryCursor("abcdef0123456789", DiscoverySort.NEWEST, null, ID, AS_OF);

        assertThatThrownBy(() -> cursor.requireMatches("0000000000000000", DiscoverySort.NEWEST))
                .isInstanceOfSatisfying(InvalidCursorException.class, e -> assertThat(e.isMismatch())
                        .isTrue());
    }

    @Test
    @DisplayName("a token that is not a cursor is refused, and not as a mismatch")
    void rubbishIsRefused() {
        // Two different codes reach the client, because the fixes differ: a mismatch
        // means "drop the cursor when the query changes", and this means "start again
        // from the first page".
        assertThatThrownBy(() -> DiscoveryCursor.decode("not-a-cursor"))
                .isInstanceOfSatisfying(InvalidCursorException.class, e -> assertThat(e.isMismatch())
                        .isFalse());
        assertThatThrownBy(() -> DiscoveryCursor.decode("")).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a cursor from an earlier encoding is refused rather than misread")
    void anOlderVersionIsRefused() {
        // During the minutes a rolling deployment has both versions serving, a client
        // can send a cursor the other release wrote. Refusing it costs one restarted
        // scroll; misreading it would silently repeat or drop cards.
        String forged = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("0" + (char) 0x1f + "abcdef0123456789" + (char) 0x1f + "newest" + (char) 0x1f + "0"
                                + (char) 0x1f + "" + (char) 0x1f + ID + (char) 0x1f + AS_OF)
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> DiscoveryCursor.decode(forged)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("the same filters in a different order fingerprint the same")
    void theFingerprintIgnoresTheOrderOfASet() {
        // Otherwise two clients that built the same filter URL in different orders
        // would be told their cursors do not match, and a shareable filter URL
        // (D-12) would be sensitive to the order somebody's checkbox list emitted.
        String one = DiscoveryQuery.builder()
                .tagSlugs(Set.of("ceramics", "handmade"))
                .build()
                .fingerprint();
        String other = DiscoveryQuery.builder()
                .tagSlugs(Set.of("handmade", "ceramics"))
                .build()
                .fingerprint();

        assertThat(one).isEqualTo(other);
    }

    @Test
    @DisplayName("the fingerprint ignores the page size and the language, and nothing else")
    void theFingerprintCoversWhatChangesTheResults() {
        DiscoveryQuery base = DiscoveryQuery.builder().build();

        // Changing the page size mid-scroll is legitimate and leaves the keyset
        // valid; changing the language changes labels on facets and never the
        // membership or the order of the results.
        assertThat(DiscoveryQuery.builder().limit(50).build().fingerprint()).isEqualTo(base.fingerprint());
        assertThat(DiscoveryQuery.builder().locale("en").build().fingerprint()).isEqualTo(base.fingerprint());

        // Everything that does change them does change the fingerprint.
        assertThat(DiscoveryQuery.builder()
                        .sort(DiscoverySort.MOST_FUNDED)
                        .build()
                        .fingerprint())
                .isNotEqualTo(base.fingerprint());
        assertThat(DiscoveryQuery.builder()
                        .statuses(Set.of(DiscoveryStatus.LIVE))
                        .build()
                        .fingerprint())
                .isNotEqualTo(base.fingerprint());
        assertThat(DiscoveryQuery.builder()
                        .categorySlugs(Set.of("games"))
                        .build()
                        .fingerprint())
                .isNotEqualTo(base.fingerprint());
    }

    @Test
    @DisplayName("a page size outside the bounds is clamped rather than refused")
    void theLimitIsClamped() {
        // A client asking for two hundred cards wants a page. Giving it the maximum
        // is more useful than an error, and the ceiling is what stops one request
        // becoming a scan and a response body of several megabytes.
        assertThat(DiscoveryQuery.builder().limit(10_000).build().limit()).isEqualTo(DiscoveryQuery.MAX_LIMIT);
        assertThat(DiscoveryQuery.builder().limit(0).build().limit()).isEqualTo(DiscoveryQuery.MIN_LIMIT);
        assertThat(DiscoveryQuery.builder().build().limit()).isEqualTo(DiscoveryQuery.DEFAULT_LIMIT);
    }
}
