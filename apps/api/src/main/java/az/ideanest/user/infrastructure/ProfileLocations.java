package az.ideanest.user.infrastructure;

import az.ideanest.user.application.ProfileLocation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * V16's closed vocabulary of places, read by slug and by identifier — §4.2's P-02 (#276).
 *
 * <h2>Why this module reads a table it did not create</h2>
 *
 * <p>{@code locations} was created by V16 for §4.3's {@code ?city=} filter and is read
 * there by {@code PostgresSearchService}. It belongs to no module's entity model: there is
 * no {@code Location} class anywhere in this service, and discovery reaches it in SQL for
 * the same reason this does.
 *
 * <p>The alternative was a published question in {@code shared} answered by the discovery
 * module, which is the shape {@code ProjectSummaries} and {@code ProjectAudiences} take and
 * which {@code ModuleBoundaryTests} asserts. It is refused here, and the distinction is the
 * one {@code PublicProfiles} already draws about counts: that pattern exists for rows a
 * module <em>owns</em> and enforces rules over — a campaign's title, a campaign's backers —
 * where reading them from elsewhere would duplicate the rules. Eighteen seeded rows of
 * reference data with no write path, no state and no visibility rule are not that. They are
 * a gazetteer, and the precedent for reading one directly is already in this schema:
 * {@code categories} is the project module's table and discovery reads it for facet counts.
 * Publishing a contract to look up a city would put a permanent surface in {@code shared}
 * for a join, which is exactly how {@code shared} acquires a feature.
 *
 * <p>What that costs is honest to state: two modules now know the shape of these three
 * columns, and a migration that renamed one would have to change both. That is the same
 * exposure discovery already has, and it is bounded by the table being reference data
 * nobody is going to restructure without noticing.
 *
 * <p>No Java dependency crosses a module boundary here, so {@code ModuleBoundaryTests}
 * has nothing to say about it either way — which is precisely why the reasoning is written
 * down rather than left to the test.
 *
 * <h2>The name is always the endonym</h2>
 *
 * <p>{@code location_translations} holds a name per locale and this reads the {@code az}
 * row, always. {@link ProfileLocation} carries the argument: a per-reader name would make
 * the public profile response vary per reader and cost it the shared cache. The
 * {@code COALESCE} to the slug is a backstop for a place added later without its mandatory
 * endonym — a profile that says {@code gence} is wrong in a small way, and one that says
 * {@code null} is a client rendering an empty chip.
 */
@Repository
public class ProfileLocations {

    /**
     * §21.1's default and V16's mandatory translation row. Named rather than inlined so
     * that the two queries below cannot come to disagree about it.
     */
    private static final String ENDONYM_LOCALE = "az";

    private static final String SELECT =
            """
            SELECT l.id AS id, l.slug AS slug, COALESCE(t.name, l.slug) AS name
              FROM locations l
              LEFT JOIN location_translations t
                     ON t.location_id = l.id
                    AND t.locale = :locale
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ProfileLocations(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The place a client named, or empty.
     *
     * <p>Empty is what makes an unknown slug a 400 that says so rather than a silently
     * ignored field. {@code ProfileEditing} is where that decision is taken and argued.
     */
    public Optional<ProfileLocation> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return first(jdbc.query(
                SELECT + " WHERE l.slug = :slug",
                Map.of("locale", ENDONYM_LOCALE, "slug", slug),
                (row, index) -> new ProfileLocation(
                        row.getObject("id", UUID.class), row.getString("slug"), row.getString("name"))));
    }

    /**
     * The place a stored {@code users.location_id} points at, or empty.
     *
     * <p>Empty here means the column holds an identifier the vocabulary no longer has,
     * which the foreign key makes impossible and which is therefore read as "no location"
     * rather than as an error: a profile page that failed because a gazetteer was edited
     * would be a page that fails.
     */
    public Optional<ProfileLocation> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return first(jdbc.query(
                SELECT + " WHERE l.id = :id",
                Map.of("locale", ENDONYM_LOCALE, "id", id),
                (row, index) -> new ProfileLocation(
                        row.getObject("id", UUID.class), row.getString("slug"), row.getString("name"))));
    }

    private static Optional<ProfileLocation> first(List<ProfileLocation> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
