package az.ideanest.project.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * A campaign.
 *
 * <p><strong>There is no {@code setState}.</strong> The only way the state
 * changes is {@link #applyTransition(ProjectState, Instant)}, which refuses any
 * edge {@link ProjectStateMachine} does not allow, and the only caller of that
 * is {@code ProjectTransitionService} — which additionally writes the audit row
 * inside the same transaction. A setter would make a state change reachable from
 * anywhere, and the transitions table would then be a record of the changes
 * somebody remembered to record.
 *
 * <p>The creator, the category, and the subcategory are held as identifiers
 * rather than as associations. {@code users} belongs to another module and
 * mapping a {@code @ManyToOne} to it would reach into that module's domain,
 * which is the boundary {@code ModuleBoundaryTests} exists to keep. The
 * taxonomy is in this module, but nothing here needs a category's name, and an
 * association would load one on every editor request that only wanted to echo
 * the identifier back.
 *
 * <p>Money is {@link BigDecimal} against a {@code numeric(14,2)} column, never a
 * double. On a funding platform a rounding error is somebody's pledge.
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private UUID creatorId;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "blurb")
    private String blurb;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "subcategory_id")
    private UUID subcategoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private ProjectState state;

    @Column(name = "goal_amount")
    private BigDecimal goalAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    /**
     * Denormalised from the pledge ledger and maintained by the pledge module.
     * Read here and never written: the ledger is the source of truth, and a
     * campaign that could adjust its own total would be a campaign that can
     * decide it succeeded.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "pledged_amount", nullable = false, insertable = false, updatable = false)
    private BigDecimal pledgedAmount;

    @Generated(event = EventType.INSERT)
    @Column(name = "backers_count", nullable = false, insertable = false, updatable = false)
    private int backersCount;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "scheduled_launch_at")
    private Instant scheduledLaunchAt;

    @Column(name = "launched_at")
    private Instant launchedAt;

    @Column(name = "deadline")
    private Instant deadline;

    /**
     * The story document, as JSON. Opaque to this module until #35, which owns
     * its schema and validates it — mapped as a string so that autosave can
     * store a draft document before that validator exists, and as {@code jsonb}
     * in the database so it can be queried and indexed without a rewrite.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "story")
    private String story;

    @Column(name = "risks")
    private String risks;

    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Column(name = "cover_image_width")
    private Integer coverImageWidth;

    @Column(name = "cover_image_height")
    private Integer coverImageHeight;

    @Column(name = "late_pledge_enabled", nullable = false)
    private boolean latePledgeEnabled;

    @Column(name = "late_pledge_ends_at")
    private Instant latePledgeEndsAt;

    /**
     * Both timestamps belong to the database — {@code created_at} to a default and
     * {@code updated_at} to a trigger, so that an application which forgot to set
     * one could not produce a row that lies about when it changed.
     *
     * <p>{@link Generated} is what makes them readable in the same request that
     * wrote them. Without it Hibernate never selects the columns back, and the
     * response to a create or a save would report {@code null} for both — which the
     * editor shows as "never saved" immediately after saving.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Project() {
        // JPA.
    }

    private Project(UUID id, UUID creatorId, String title, String slug, String currency) {
        this.id = id;
        this.creatorId = creatorId;
        this.title = title;
        this.slug = slug;
        this.currency = currency;
        this.state = ProjectStateMachine.initial();
        this.latePledgeEnabled = false;
    }

    /**
     * A new draft, with a title and nothing else.
     *
     * <p>Everything §5.3 requires is absent and nullable, deliberately: the
     * editor's first screen is reached by creating the campaign, so a draft that
     * could not exist without a goal, a category, and a cover image would mean
     * the creator had to fill a form before they could open the form. The
     * checklist (#37) is what refuses to submit an incomplete one.
     *
     * @param currency what the goal and every pledge is denominated in. Fixed at
     *     creation because changing it later would reprice commitments already
     *     made
     */
    public static Project open(UUID creatorId, String title, String slug, String currency) {
        return new Project(Identifiers.newIdentifier(), creatorId, title, slug, currency);
    }

    /**
     * Moves the campaign, refusing any edge §6.1 does not permit.
     *
     * <p><strong>Call this through {@code ProjectTransitionService} and nowhere
     * else.</strong> That service validates the edge before it gets here, so the
     * check below is the second of two — kept because it is the one that cannot
     * be forgotten by a future caller, and because the entity refusing an
     * impossible state is what makes the invariant a property of the object
     * rather than of one service method.
     *
     * <p>Entering {@link ProjectState#LIVE} is the one transition with side
     * effects on other columns: it stamps the launch and computes the deadline
     * from the duration the creator chose. Both are then frozen — §5.3 makes the
     * goal and the deadline immutable after launch — which is why the deadline
     * is stored rather than derived on read.
     *
     * @throws IllegalStateException when the edge is not allowed, or when going
     *     live without the duration a deadline is computed from. Both are bugs
     *     in the caller by the time they reach here, which is why neither is a
     *     checked or a translated exception
     */
    public void applyTransition(ProjectState next, Instant at) {
        Objects.requireNonNull(next, "A transition needs a target state");
        Objects.requireNonNull(at, "A transition needs the moment it happened");

        if (!ProjectStateMachine.isAllowed(state, next)) {
            throw new IllegalStateException(
                    "A project cannot move from " + state + " to " + next + "; §6.1 does not allow it");
        }

        if (next == ProjectState.LIVE) {
            if (durationDays == null) {
                throw new IllegalStateException("A project cannot go live without a duration to end from");
            }
            this.launchedAt = at;
            // Days rather than a period in months: a campaign is a countdown the
            // page shows in hours near the end, and adding days to an instant
            // keeps that arithmetic exact regardless of the viewer's zone.
            this.deadline = at.plus(durationDays, ChronoUnit.DAYS);
        }

        this.state = next;
    }

    /** Whether this account is the campaign's creator. Authorisation is decided in {@code ProjectAccess}. */
    public boolean isCreatedBy(UUID accountId) {
        return creatorId.equals(accountId);
    }

    public boolean isLive() {
        return state == ProjectState.LIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBlurb() {
        return blurb;
    }

    public void setBlurb(String blurb) {
        this.blurb = blurb;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getSubcategoryId() {
        return subcategoryId;
    }

    /**
     * Files the campaign under a category, and optionally a subcategory.
     *
     * <p>Both together, never separately: the database refuses a subcategory
     * whose parent is not this category, and two setters would let a caller
     * write the halves in an order that violates it — or leave a subcategory
     * behind after the category moved.
     */
    public void file(UUID categoryId, UUID subcategoryId) {
        if (categoryId == null && subcategoryId != null) {
            throw new IllegalArgumentException("A subcategory without a category is not a filing");
        }
        this.categoryId = categoryId;
        this.subcategoryId = subcategoryId;
    }

    public ProjectState getState() {
        return state;
    }

    public BigDecimal getGoalAmount() {
        return goalAmount;
    }

    public void setGoalAmount(BigDecimal goalAmount) {
        this.goalAmount = goalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getPledgedAmount() {
        return pledgedAmount;
    }

    public int getBackersCount() {
        return backersCount;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public Instant getScheduledLaunchAt() {
        return scheduledLaunchAt;
    }

    public void setScheduledLaunchAt(Instant scheduledLaunchAt) {
        this.scheduledLaunchAt = scheduledLaunchAt;
    }

    public Instant getLaunchedAt() {
        return launchedAt;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public String getStory() {
        return story;
    }

    public void setStory(String story) {
        this.story = story;
    }

    public String getRisks() {
        return risks;
    }

    public void setRisks(String risks) {
        this.risks = risks;
    }

    /** The cover image, or null when none has been set. The three columns move together. */
    public CoverImage getCoverImage() {
        if (coverImageUrl == null || coverImageWidth == null || coverImageHeight == null) {
            return null;
        }
        return new CoverImage(coverImageUrl, coverImageWidth, coverImageHeight);
    }

    public void setCoverImage(CoverImage coverImage) {
        if (coverImage == null) {
            this.coverImageUrl = null;
            this.coverImageWidth = null;
            this.coverImageHeight = null;
            return;
        }
        this.coverImageUrl = coverImage.url();
        this.coverImageWidth = coverImage.width();
        this.coverImageHeight = coverImage.height();
    }

    public boolean isLatePledgeEnabled() {
        return latePledgeEnabled;
    }

    public void setLatePledgeEnabled(boolean latePledgeEnabled) {
        this.latePledgeEnabled = latePledgeEnabled;
    }

    public Instant getLatePledgeEndsAt() {
        return latePledgeEndsAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        // The identifier, which is assigned before persistence, so an unsaved
        // and a reloaded instance of the same campaign compare equal.
        if (this == other) {
            return true;
        }
        return other instanceof Project project && Objects.equals(id, project.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No title: an unlaunched campaign is confidential, and this lands in
        // logs. The state is here because it is what log lines about a project
        // are almost always asking about.
        return "Project[id=" + id + ", state=" + state + "]";
    }
}
