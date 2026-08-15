package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.ProjectStateTransition;
import java.util.List;
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
}
