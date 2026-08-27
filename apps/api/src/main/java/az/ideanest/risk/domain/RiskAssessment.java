package az.ideanest.risk.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * What was noticed about one action, at one moment — issue #108.
 *
 * <p><strong>A row per assessment, not a column on the thing assessed.</strong> A score is
 * a judgement made from facts that move: the same pledge scores differently tomorrow
 * because the account is a day older. A column would be overwritten by the second
 * assessment, and the first is exactly the record somebody wants when they are asking why
 * a charge that was flagged was allowed through. V57 carries the argument in full.
 *
 * <p>Only {@code reviewedAt} and {@code reviewedBy} are mutable, and they move once: a
 * member of staff looked. Nothing else about an assessment can be edited, because an
 * assessment that could be edited is not evidence.
 */
@Entity
@Table(name = "risk_assessments")
public class RiskAssessment {

    /** The only subject type today. See V57 on why the column exists anyway. */
    public static final String PLEDGE = "pledge";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subject_type", nullable = false, updatable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "subject_user_id", nullable = false, updatable = false)
    private UUID subjectUserId;

    @Column(name = "score", nullable = false, updatable = false)
    private short score;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false)
    private RiskDecision decision;

    /**
     * The findings, as the JSON document V57 describes. Opaque here.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} and not {@code columnDefinition}: the former
     * tells the driver what the parameter is, the latter only tells schema generation what
     * to create — and nothing here generates schema. Without it the insert binds a
     * {@code varchar} and Postgres refuses it with "column is of type jsonb but expression
     * is of type character varying". {@code Notification.params} carries the same
     * annotation for the same reason.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", nullable = false, updatable = false)
    private String findings;

    @Column(name = "signals_unavailable", nullable = false, updatable = false)
    private short signalsUnavailable;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "assessed_at", nullable = false, updatable = false)
    private Instant assessedAt;

    protected RiskAssessment() {
        // JPA.
    }

    private RiskAssessment(
            String subjectType,
            UUID subjectId,
            UUID projectId,
            UUID subjectUserId,
            int score,
            RiskDecision decision,
            String findings,
            int signalsUnavailable,
            Instant assessedAt) {
        this.id = Identifiers.newIdentifier();
        this.subjectType = Objects.requireNonNull(subjectType, "An assessment is about something");
        this.subjectId = Objects.requireNonNull(subjectId, "An assessment is about something");
        this.projectId = projectId;
        this.subjectUserId = Objects.requireNonNull(subjectUserId, "An assessment is about an action");
        this.score = (short) score;
        this.decision = Objects.requireNonNull(decision, "An assessment reaches a decision");
        this.findings = Objects.requireNonNull(findings, "An assessment says what it looked at");
        this.signalsUnavailable = (short) signalsUnavailable;
        /*
         * Truncated to what `timestamptz` holds. Without it the entity in memory and the
         * row disagree in the last three digits, and `assessed_at` is half of this table's
         * uniqueness constraint -- so a re-read would not match the row just written.
         */
        this.assessedAt = assessedAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** An assessment of one pledge. */
    public static RiskAssessment ofPledge(
            UUID pledgeId,
            UUID projectId,
            UUID backerId,
            int score,
            RiskDecision decision,
            String findings,
            int signalsUnavailable,
            Instant assessedAt) {
        return new RiskAssessment(
                PLEDGE, pledgeId, projectId, backerId, score, decision, findings, signalsUnavailable, assessedAt);
    }

    /**
     * Marks that somebody looked.
     *
     * <p>It does not record what they concluded, and that is deliberate: the conclusion is
     * an action — a refund, a suspension, a note on a support ticket — and each of those is
     * already recorded by the module that took it, with its own audit row. A second,
     * free-text verdict here would be a place for the two to disagree.
     */
    public void reviewedBy(UUID staffId, Instant at) {
        this.reviewedBy = Objects.requireNonNull(staffId, "A review has a reviewer");
        this.reviewedAt = at.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSubjectUserId() {
        return subjectUserId;
    }

    public int getScore() {
        return score;
    }

    public RiskDecision getDecision() {
        return decision;
    }

    public String getFindings() {
        return findings;
    }

    public int getSignalsUnavailable() {
        return signalsUnavailable;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }

    @Override
    public String toString() {
        // No subject user and no findings: a finding is a sentence about behaviour, and
        // this ends up in log lines (§17.4).
        return "RiskAssessment[id=" + id + ", score=" + score + ", decision=" + decision + "]";
    }
}
