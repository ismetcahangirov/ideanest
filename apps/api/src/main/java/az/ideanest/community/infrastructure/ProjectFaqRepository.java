package az.ideanest.community.infrastructure;

import az.ideanest.community.domain.ProjectFaq;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A campaign's FAQ entries, in the creator's order.
 *
 * <p>One query, because there is one question asked of this table: "what is on this
 * campaign's FAQ tab". The public read, the editor's read and the reorder's own load are
 * all that query, and they all need the whole list — the reorder because it rewrites
 * every position, and the public read because §10.2 gives this endpoint no cursor.
 *
 * <p><strong>Ordered by {@code created_at} after {@code sort_order}</strong>, which
 * {@code project_faqs_project_sort_idx} does not itself provide. The positions are not
 * unique — a reorder rewrites them all and a unique constraint would refuse the
 * intermediate states — so a campaign whose entries have never been reordered has every
 * position at its insertion value and needs a tiebreak that is stable across reads. The
 * alternative is a list whose order changes between two requests that changed nothing,
 * which the ETag on the public read would then report as an edit.
 *
 * <p>The create path reads the same list rather than asking for a count and a maximum
 * separately: it needs both — how many there are, for the cap, and where the last one
 * sits, for the new entry's position — and one bounded read that cannot disagree with
 * itself is cheaper than two that can.
 */
public interface ProjectFaqRepository extends JpaRepository<ProjectFaq, UUID> {

    List<ProjectFaq> findByProjectIdOrderBySortOrderAscCreatedAtAsc(UUID projectId);
}
