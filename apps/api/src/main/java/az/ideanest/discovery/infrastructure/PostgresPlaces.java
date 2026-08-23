package az.ideanest.discovery.infrastructure;

import az.ideanest.discovery.application.Places;
import az.ideanest.discovery.domain.Place;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link Places} over V16's two tables.
 *
 * <p><strong>Two joins rather than one, because the fallback chain has three links.</strong>
 * The requested locale, then the {@code az} endonym V16 makes mandatory, then the slug. A
 * single join on the requested locale with a {@code COALESCE} to the slug would skip the
 * middle link and answer a Russian-speaking reader {@code mingecevir} for a city whose
 * Azerbaijani name is right there — which is worse than the endonym, because the endonym is
 * at least a name somebody wrote.
 *
 * <p>SQL rather than an entity, exactly as {@code PostgresSearchService} reads the same
 * table and for the reason {@code ProfileLocations} states about reading it from the user
 * module: there is no {@code Location} class in this service, and reference data with no
 * write path does not earn one.
 */
@Repository
public class PostgresPlaces implements Places {

    /** §21.1's primary language, and V16's mandatory translation row. */
    private static final String ENDONYM_LOCALE = "az";

    private static final String SELECT =
            """
            SELECT l.slug AS slug,
                   COALESCE(asked.name, endonym.name, l.slug) AS name
              FROM locations l
              LEFT JOIN location_translations asked
                     ON asked.location_id = l.id
                    AND asked.locale = :locale
              LEFT JOIN location_translations endonym
                     ON endonym.location_id = l.id
                    AND endonym.locale = :endonym
             ORDER BY 2
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresPlaces(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Place> all(String locale) {
        return jdbc.query(
                SELECT,
                Map.of("locale", locale == null ? ENDONYM_LOCALE : locale, "endonym", ENDONYM_LOCALE),
                (row, index) -> new Place(row.getString("slug"), row.getString("name")));
    }
}
