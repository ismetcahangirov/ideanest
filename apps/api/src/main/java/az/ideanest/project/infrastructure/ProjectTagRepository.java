package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.ProjectTag;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Which campaigns carry which tags.
 *
 * <p>Two directions, because the table is read from both ends and each has its
 * own index behind it: the campaign editor asks "what is this campaign tagged
 * with" and reads the primary key, while discovery's tag filter asks "which
 * campaigns carry this tag" and reads {@code project_tags_tag_idx}. Without the
 * second index that filter is a sequential scan of every edge on the platform,
 * which is why V11 creates it rather than leaving it to the query planner.
 */
public interface ProjectTagRepository extends JpaRepository<ProjectTag, ProjectTag.Key> {

    List<ProjectTag> findByIdProjectId(UUID projectId);

    List<ProjectTag> findByIdTagId(UUID tagId);
}
