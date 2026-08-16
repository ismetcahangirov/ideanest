package az.ideanest.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts a collection into the state a test needs to start from, by writing the rows.
 *
 * <p><strong>For the suites that are testing the reads.</strong> The honest way to
 * create a collection is the admin API, and {@code CurationApiTests} takes it, because
 * that path is what it is checking. A suite about what the public may see is not:
 * driving every fixture through {@code /v1/admin/collections} would make each of its
 * tests depend on the moderator configuration and on a second account sharing one
 * sign-in rate limiter, for a collection whose existence is a precondition rather than
 * a subject.
 *
 * <p>Every check constraint in V14 still applies, so the rows that result are ones the
 * application could have produced — including the {@code az} title, which this fixture
 * always writes for the same reason {@code CurationService} requires it.
 *
 * <p>What is <em>not</em> written is the audit row. A collection created this way has a
 * history that does not mention its creation, so a test that reads
 * {@code curation_events} must not use this.
 */
public final class Curations {

    private Curations() {
    }

    /** A collection, not yet published and with nothing in it. */
    public static Collection collection(DataSource dataSource, String slug) {
        return new Collection(dataSource, slug);
    }

    /** @see #collection */
    public static final class Collection {

        private final JdbcTemplate jdbc;
        private final String slug;
        private final UUID id = UUID.randomUUID();
        private final Map<String, String[]> copy = new LinkedHashMap<>();

        private String kind = "THEMED";
        private boolean published;
        private boolean grantsBadge;
        private Instant opensAt;
        private Instant closesAt;
        private int sortOrder;

        private Collection(DataSource dataSource, String slug) {
            this.jdbc = new JdbcTemplate(dataSource);
            this.slug = slug;
            // Every collection has an az title; §21.1 and V14. Overridden by title().
            this.copy.put("az", new String[] {slug, null});
        }

        /** {@code STAFF_SELECTION}, {@code THEMED}, or {@code OPEN_CALL}. */
        public Collection kind(String value) {
            this.kind = value;
            return this;
        }

        /** Whether membership badges a campaign — §3.2, and what {@code showOnly=featured} reads. */
        public Collection grantsBadge() {
            this.grantsBadge = true;
            return this;
        }

        public Collection published() {
            this.published = true;
            return this;
        }

        public Collection opensAt(Instant value) {
            this.opensAt = value;
            return this;
        }

        public Collection closesAt(Instant value) {
            this.closesAt = value;
            return this;
        }

        public Collection sortOrder(int value) {
            this.sortOrder = value;
            return this;
        }

        /** A title in one language. Replaces the default {@code az} title when the locale is az. */
        public Collection title(String locale, String value) {
            String description = copy.containsKey(locale) ? copy.get(locale)[1] : null;
            copy.put(locale, new String[] {value, description});
            return this;
        }

        public Collection description(String locale, String value) {
            String title = copy.containsKey(locale) ? copy.get(locale)[0] : slug;
            copy.put(locale, new String[] {title, value});
            return this;
        }

        /** Removes every translation, for the test that asserts the slug is the last resort. */
        public Collection withoutCopy() {
            copy.clear();
            return this;
        }

        public UUID insert() {
            jdbc.update(
                    """
                    INSERT INTO collections (
                        id, slug, kind, published_at, opens_at, closes_at, grants_badge, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    slug,
                    kind,
                    published ? OffsetDateTime.now(ZoneOffset.UTC) : null,
                    opensAt == null ? null : OffsetDateTime.ofInstant(opensAt, ZoneOffset.UTC),
                    closesAt == null ? null : OffsetDateTime.ofInstant(closesAt, ZoneOffset.UTC),
                    grantsBadge,
                    sortOrder);
            for (Map.Entry<String, String[]> entry : copy.entrySet()) {
                jdbc.update(
                        "INSERT INTO collection_translations (collection_id, locale, title, description)"
                                + " VALUES (?, ?, ?, ?)",
                        id,
                        entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1]);
            }
            return id;
        }
    }

    /**
     * Curates campaigns into a collection, in the order they are given.
     *
     * <p>Positions are the sparse 10, 20, 30 the admin API writes, so a fixture and a
     * curated list built over HTTP are the same rows.
     */
    public static void members(DataSource dataSource, UUID collectionId, UUID... projectIds) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int position = 0;
        for (UUID projectId : projectIds) {
            position += 10;
            jdbc.update(
                    "INSERT INTO collection_projects (collection_id, project_id, position) VALUES (?, ?, ?)",
                    collectionId,
                    projectId,
                    position);
        }
    }
}
