package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.StoryVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Story versions, by the three questions asked of them.
 *
 * <p>Newest first everywhere, which is both the order the index is built in and
 * the order a history is read in: nobody opens a version list to look at the
 * oldest draft.
 *
 * <p>There is deliberately no update method. {@code StoryVersion} has no setters
 * either — see that class for why a version that could be edited would stop being
 * a history — and Spring Data will happily generate an update from a derived
 * name, so the absence has to be deliberate in both places.
 */
public interface StoryVersionRepository extends JpaRepository<StoryVersion, UUID> {

    /** The whole history of one story, newest first. Capped by retention, so unpaged. */
    List<StoryVersion> findByProjectIdOrderByVersionNumberDesc(UUID projectId);

    Optional<StoryVersion> findByProjectIdAndVersionNumber(UUID projectId, int versionNumber);

    /**
     * The newest version, or empty when the story has never been saved twice.
     *
     * <p>Answers both questions the write path has: what number comes next, and
     * whether enough time has passed since the last one to write another.
     */
    Optional<StoryVersion> findFirstByProjectIdOrderByVersionNumberDesc(UUID projectId);

    /**
     * Prunes everything older than the retention window.
     *
     * <p>A bulk delete rather than loading the rows and removing them one at a
     * time: the documents are the largest thing in the table and none of them is
     * being read. It bypasses the persistence context, which is safe here because
     * nothing in the same transaction holds one of the rows it removes — the
     * transaction that runs this has just inserted the newest version, which is by
     * definition not among them.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM StoryVersion v WHERE v.projectId = :projectId AND v.versionNumber < :oldestKept")
    int deleteOlderThan(@Param("projectId") UUID projectId, @Param("oldestKept") int oldestKept);
}
