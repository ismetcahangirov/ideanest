package az.ideanest.community.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One question and answer on a campaign's FAQ tab. §4.4's FAQ tab, §4.7's CD-15.
 *
 * <p><strong>Mutable, unlike {@code ProjectUpdate}, and the difference is the
 * specification rather than taste.</strong> §10.2 gives an update a create endpoint and
 * a read endpoint and nothing else, because an update is a statement made to people who
 * have already read it. An FAQ entry is the opposite kind of text: it is the current
 * answer to a question people keep asking, and §10.2 gives it
 * {@code PATCH /v1/faqs/{id}} and {@code DELETE /v1/faqs/{id}} because an answer that
 * has stopped being true is worse than no answer at all.
 *
 * <p><strong>Every way in goes through {@link FaqContent}.</strong> The factory and both
 * setters call it, so there is no construction and no edit the rules have not seen — a
 * second write path added later inherits the check instead of having to remember it.
 *
 * <p><strong>No author.</strong> §4.4 calls the list "creator-managed", so an entry is
 * the campaign's answer rather than one person's; {@code project_updates} carries an
 * {@code author_id} because an update is signed, and this deliberately does not.
 *
 * <p><strong>No optimistic locking.</strong> {@code reward_tiers} has a {@code version}
 * column because a creator editing a tier races a backer's checkout reserving a place in
 * it. Nothing outside the editor writes an FAQ entry, so two of a creator's own tabs are
 * last-write-wins — which is what {@code ProjectAccess#requireEditable} already says an
 * autosaving editor means.
 */
@Entity
@Table(name = "project_faqs")
public class ProjectFaq {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "question", nullable = false)
    private String question;

    @Column(name = "answer", nullable = false)
    private String answer;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * The database's, through a default, so an entry cannot claim to have been written
     * at a time the application chose. {@link Generated} is what makes it readable in
     * the request that wrote it.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by {@code project_faqs_set_updated_at}, for the same reason. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ProjectFaq() {
        // JPA.
    }

    private ProjectFaq(UUID projectId, String question, String answer, int sortOrder) {
        this.id = Identifiers.newIdentifier();
        this.projectId = projectId;
        this.question = question;
        this.answer = answer;
        this.sortOrder = sortOrder;
    }

    /**
     * Writes an entry.
     *
     * @param sortOrder where it goes in the creator's list, allocated by
     *     {@code ProjectFaqService} as one past the last. The entity cannot see the
     *     other rows, and a position invented locally is the kind of thing that quietly
     *     becomes a duplicate
     */
    public static ProjectFaq write(UUID projectId, String question, String answer, int sortOrder) {
        Objects.requireNonNull(projectId, "An FAQ entry belongs to a campaign");
        if (sortOrder < 0) {
            throw new IllegalArgumentException("Positions are rewritten from zero");
        }
        return new ProjectFaq(projectId, FaqContent.question(question), FaqContent.answer(answer), sortOrder);
    }

    /** Rewrites the question, through the same rules the entry was created under. */
    public void rephrase(String value) {
        this.question = FaqContent.question(value);
    }

    /** Rewrites the answer, through the same rules the entry was created under. */
    public void answerWith(String value) {
        this.answer = FaqContent.answer(value);
    }

    /**
     * Moves the entry to a position.
     *
     * <p>Called only by a reorder, which rewrites every entry of the campaign from zero
     * — see {@code ProjectFaqService#reorder} for why the whole list moves rather than
     * one row.
     */
    public void moveTo(int position) {
        if (position < 0) {
            throw new IllegalArgumentException("Positions are rewritten from zero");
        }
        this.sortOrder = position;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ProjectFaq faq && Objects.equals(id, faq.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No question and no answer: an unlaunched campaign's FAQ is text nobody has
        // been shown yet, and this lands in logs.
        return "ProjectFaq[id=" + id + ", project=" + projectId + ", position=" + sortOrder + "]";
    }
}
