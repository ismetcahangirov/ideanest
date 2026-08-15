package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.ActorRole;
import az.ideanest.project.domain.ProjectStateTransition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The audit trail behind {@code projects.state}.
 *
 * <p>Insert and read only. {@link JpaRepository} does bring {@code delete} and
 * {@code save}-as-update along with it, which is the cost of the base interface;
 * what stops them being used is that the entity has no setters and the database
 * has no code path that calls them. The table's promise is enforced where it can
 * be enforced — in the entity's immutability and in review — rather than
 * described here and contradicted by a convenient method later.
 */
public interface ProjectStateTransitionRepository extends JpaRepository<ProjectStateTransition, UUID> {

    /**
     * One campaign's history, oldest first.
     *
     * <p>Ascending because it is read as a narrative: created, submitted, changes
     * requested, submitted again, approved. A moderation review reads it from the
     * beginning, not from the end.
     */
    List<ProjectStateTransition> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    /**
     * The most recent decision platform staff took on a campaign, if there has
     * been one.
     *
     * <p>Descending, and one row, because this answers a different question from
     * the history above: the creator's review screen shows what they have to act
     * on, not the narrative. Reading the whole history and taking the last
     * matching row would be the same answer at the cost of loading every
     * transition a long-running campaign has accumulated, on a screen the editor
     * opens often.
     *
     * <p>Selected by actor role rather than by target state so that it stays
     * correct when moderation gains an outcome — a suspension is a staff decision
     * about a live campaign, and the creator needs to read its note for the same
     * reason they need to read a change request's.
     */
    Optional<ProjectStateTransition> findFirstByProjectIdAndActorRoleOrderByCreatedAtDesc(
            UUID projectId, ActorRole actorRole);
}
