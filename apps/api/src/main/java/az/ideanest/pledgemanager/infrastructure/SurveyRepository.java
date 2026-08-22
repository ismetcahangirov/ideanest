package az.ideanest.pledgemanager.infrastructure;

import az.ideanest.pledgemanager.domain.Survey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** A campaign's surveys — §4.8's PM-01. */
public interface SurveyRepository extends JpaRepository<Survey, UUID> {

    /** Newest first, which is the creator's list. */
    @Query("SELECT s FROM Survey s WHERE s.projectId = :projectId ORDER BY s.createdAt DESC, s.id DESC")
    List<Survey> findByProject(@Param("projectId") UUID projectId);

    /**
     * One survey, and only if it is this campaign's.
     *
     * <p>The campaign is part of the query rather than a check on the result: a caller
     * that loaded by identifier and then compared would already have read another
     * campaign's row into memory, which is the shape of every cross-tenant leak that
     * ever shipped.
     */
    @Query("SELECT s FROM Survey s WHERE s.id = :surveyId AND s.projectId = :projectId")
    Optional<Survey> findOnProject(@Param("projectId") UUID projectId, @Param("surveyId") UUID surveyId);

    /** Every sent survey on any of these campaigns — what a backer's list is built from. */
    @Query("SELECT s FROM Survey s WHERE s.projectId IN :projectIds AND s.sentAt IS NOT NULL"
            + " ORDER BY s.sentAt DESC, s.id DESC")
    List<Survey> findSentOnProjects(@Param("projectIds") Collection<UUID> projectIds);

    /**
     * Surveys the reminder sweep should consider: sent, and still open.
     *
     * <p>Ordered by the send, so that a run which reaches its own bound works through
     * the oldest first — a survey sent last month is more urgent than one sent this
     * morning, whose backers have not had time to answer.
     */
    @Query("SELECT s FROM Survey s WHERE s.sentAt IS NOT NULL AND s.sentAt < :before"
            + " AND (s.respondBy IS NULL OR s.respondBy > :now) ORDER BY s.sentAt")
    List<Survey> findOpenForReminder(@Param("now") Instant now, @Param("before") Instant before);
}
