package az.ideanest.reward.infrastructure;

import az.ideanest.reward.domain.Item;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Items, by the campaign they belong to. */
public interface ItemRepository extends JpaRepository<Item, UUID> {

    /**
     * Every item of one campaign, oldest first.
     *
     * <p>Creation order rather than alphabetical: the editor's list is one a
     * creator builds up, and a list that reshuffles as things are named is one
     * they lose their place in.
     */
    List<Item> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    /**
     * The subset of these identifiers that belongs to this campaign.
     *
     * <p>One query for a whole composition rather than one per item, and scoped to
     * the campaign so that the answer to "does this item exist" is always "for
     * this creator" — the composite foreign key refuses a cross-campaign row
     * anyway, and this is what turns that refusal into a 400 naming the item.
     */
    List<Item> findByProjectIdAndIdIn(UUID projectId, Collection<UUID> ids);

    /**
     * The item using this stock-keeping code, if any.
     *
     * <p>Scoped to the campaign, because the unique index is: two creators may both
     * call something "MUG-01". Read rather than left to the index so that a
     * collision is a 400 naming the field instead of a constraint violation and a
     * 500 — the code is the creator's own, and typing one they already used is an
     * ordinary mistake rather than an exceptional one.
     */
    Optional<Item> findByProjectIdAndSku(UUID projectId, String sku);
}
