package az.ideanest.discovery.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The ETag machinery §10.3 asks for on a public read, in one place.
 *
 * <p>Extracted from {@link DiscoveryController} when {@link SearchController}
 * arrived: two controllers serving the same shapes with two copies of a digest is
 * two chances for one of them to hash a subset of what it serves, and a tag that
 * fails to change when the part it does not cover does is a client rendering last
 * minute's cards and never being told.
 *
 * <p>A digest over what is serialised, never {@code hashCode()}: a tag has to mean
 * the same thing to every instance of the service and to the same instance after a
 * restart, and nothing guarantees a record's hash does.
 */
final class PublicReads {

    /** ASCII unit separator, for the digest. Cannot occur in a slug, a uuid, or a number. */
    static final char FIELD_SEPARATOR = (char) 0x1f;

    /** ASCII record separator, so that a row boundary is not a field boundary. */
    static final char ROW_SEPARATOR = (char) 0x1e;

    private PublicReads() {
    }

    /**
     * A tag derived from the content, deterministically.
     *
     * <p>The locale is hashed first. Two languages of one response must not share a
     * tag, or a client that asked for one revalidates the other and is told 304.
     */
    static String etagOf(String locale, String content) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. Reaching here is not a runtime condition.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] digest = sha256.digest((locale + FIELD_SEPARATOR + content).getBytes(StandardCharsets.UTF_8));
        return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
    }

    static void append(StringBuilder canonical, String... fields) {
        for (String field : fields) {
            canonical.append(field).append(FIELD_SEPARATOR);
        }
        canonical.append(ROW_SEPARATOR);
    }

    /**
     * Everything in a feed that reaches the client, in a fixed order.
     *
     * <p>Every field, not a selection: a digest over a subset is a tag that fails to
     * change when the part it does not cover does.
     */
    static String canonical(DiscoveryResponses.Feed feed) {
        StringBuilder canonical = new StringBuilder();
        for (DiscoveryResponses.Card card : feed.items()) {
            append(canonical, card.id(), card.slug(), card.creatorSlug(), card.title(), card.state(), card.badge());
            append(
                    canonical,
                    card.creator().name(),
                    card.creator().slug(),
                    card.creator().avatarUrl());
            append(
                    canonical,
                    card.image() == null ? null : card.image().url(),
                    card.image() == null ? null : String.valueOf(card.image().width()),
                    card.image() == null ? null : String.valueOf(card.image().height()));
            append(
                    canonical,
                    card.goal() == null ? null : card.goal().amount().toPlainString(),
                    card.goal() == null ? null : card.goal().currency(),
                    card.pledged().amount().toPlainString(),
                    card.pledged().currency(),
                    card.completionPercent(),
                    String.valueOf(card.backersCount()),
                    String.valueOf(card.daysLeft()),
                    String.valueOf(card.launchedAt()),
                    String.valueOf(card.deadline()));
        }
        append(canonical, feed.nextCursor());
        return canonical.toString();
    }

    static String canonical(DiscoveryResponses.Facets facets) {
        StringBuilder canonical = new StringBuilder();
        appendCounts(canonical, facets.status());
        for (DiscoveryResponses.CategoryCount category : facets.categories()) {
            append(canonical, category.slug(), category.name(), String.valueOf(category.count()));
            for (DiscoveryResponses.NamedCount subcategory : category.subcategories()) {
                append(canonical, subcategory.slug(), subcategory.name(), String.valueOf(subcategory.count()));
            }
        }
        for (DiscoveryResponses.NamedCount tag : facets.tags()) {
            append(canonical, tag.slug(), tag.name(), String.valueOf(tag.count()));
        }
        appendCounts(canonical, facets.completion());
        appendCounts(canonical, facets.goalAmount());
        appendCounts(canonical, facets.amountRaised());
        return canonical.toString();
    }

    static String canonical(DiscoveryResponses.Suggestions suggestions) {
        StringBuilder canonical = new StringBuilder();
        for (DiscoveryResponses.SuggestionItem item : suggestions.items()) {
            append(canonical, item.kind(), item.label(), item.slug(), item.parentSlug());
        }
        return canonical.toString();
    }

    private static void appendCounts(StringBuilder canonical, Iterable<DiscoveryResponses.ValueCount> counts) {
        for (DiscoveryResponses.ValueCount count : counts) {
            append(canonical, count.value(), String.valueOf(count.count()));
        }
    }
}
