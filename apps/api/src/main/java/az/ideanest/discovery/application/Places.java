package az.ideanest.discovery.application;

import az.ideanest.discovery.domain.Place;
import java.util.List;

/**
 * V16's gazetteer, as a list somebody can choose from — §4.3's location vocabulary.
 *
 * <p><strong>Why this exists at all, given that discovery already reads the table.</strong>
 * {@code PostgresSearchService} joins {@code locations} to answer {@code ?city=}, and
 * {@code DiscoveryQueryBinder} resolves a slug a caller sent. Neither of them can tell a
 * client what the slugs <em>are</em>. That was tolerable while the only consumer was a
 * filter panel that had not been built, and stopped being when #276 gave the profile editor
 * a location control: a closed vocabulary with no published index is one a client can only
 * use by hard-coding it, and a taxonomy hard-coded in a client is the thing §4.3 forbids in
 * its second sentence.
 *
 * <p><strong>Not paged, and the table is why.</strong> Eighteen rows, and the migration that
 * seeds them argues that adding one is a privileged act somebody plans rather than something
 * that happens. If that changes the answer is a cursor rather than a silent truncation,
 * which is the same position {@code CollectionController.index} takes about a curated list
 * of lists.
 */
public interface Places {

    /**
     * Every place, ordered by the name a reader will see.
     *
     * <p><strong>Ordered by name and not by slug</strong>, because the two disagree the
     * moment a name is not ASCII: {@code Gəncə} sorts under G and its slug {@code gence}
     * does too, but {@code Şəki} and {@code seki} do not land in the same place, and a
     * control whose options are alphabetical in the wrong alphabet is one people scroll
     * past what they wanted. The collation is the database's, so the order is the same for
     * every reader of one language.
     *
     * @param locale a supported code, already resolved by {@code Taxonomy.localeFor}
     */
    List<Place> all(String locale);
}
