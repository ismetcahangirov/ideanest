package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.Tag;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The free vocabulary of §4.3.
 *
 * <p>Nothing calls it yet, and that is the honest state of #41 rather than an
 * oversight: attaching a tag to a campaign is the campaign editor's field and
 * §10.2 lists no {@code /v1/tags} route, so this issue delivers the schema, the
 * entities, and this. The one finder is the one every writer will need first —
 * a tag is created only when its folded slug is not already taken, because two
 * rows for one word would split that word's campaigns across two facets.
 *
 * <p>The trigram index that autocomplete (#46) will search is on the table
 * rather than expressed here; a {@code LIKE '%…%'} finder without the surface
 * that calls it would be a query nobody has measured.
 */
public interface TagRepository extends JpaRepository<Tag, UUID> {

    /** By the folded form, which is the identity of a tag. See {@link Tag}. */
    Optional<Tag> findBySlug(String slug);
}
