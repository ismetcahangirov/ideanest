package az.ideanest.obligation.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.obligation.ObligationProperties;
import az.ideanest.obligation.domain.ObligationState;
import az.ideanest.obligation.domain.UpdateObligation;
import az.ideanest.obligation.infrastructure.UpdateObligationRepository;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §5.5's monthly update, kept — issue #437.
 *
 * <h2>Three audiences, and they need different things</h2>
 *
 * <p>{@link #forProject} and {@link #forCreator} are public reads: §22.3 asks for a fixed risk
 * statement and a visible project history, and both of those are things a stranger sees before
 * they decide to back somebody. {@link #escalated} and {@link #resolve} need
 * {@link StaffCapability#MODERATE_CONTENT}, because closing an escalation is a moderation
 * decision under §4.11's AD-02. {@link #open}, {@link #recordUpdate} and {@link #close} have no
 * caller at all — they are driven by §8.3's outbox, and there is nobody to authorise.
 *
 * <h2>Nothing here touches money or a campaign's state</h2>
 *
 * <p>Deliberately, and it is the single most important property of this module. §9.7 says a
 * creator who cannot deliver "offers a refund; the platform mediates", and suspension is a
 * moderator decision under AD-02. An automatic refund or suspension would be the platform
 * adjudicating a dispute it has told everybody it only mediates — and would contradict the
 * intermediary position epic #421 exists to establish. The strongest thing that happens here is
 * that a row stops reading {@code CURRENT} and appears in a queue.
 *
 * <h2>Why the reads are unauthenticated and that is not an oversight</h2>
 *
 * <p>A lapsed obligation is a fact about a public campaign, stated neutrally with the date of the
 * last update. It is not a sanction and not personal data — §22.3 names it as the mechanism by
 * which the platform's transparency works at all. Putting it behind authentication would mean
 * the person it is meant to inform, the one deciding whether to back this creator again, is
 * exactly the person who cannot see it.
 */
@Service
public class UpdateObligations {

    private static final Logger log = LoggerFactory.getLogger(UpdateObligations.class);

    private final UpdateObligationRepository obligations;
    private final PlatformStaff staff;
    private final AuditLog audit;
    private final ObligationProperties properties;
    private final Clock clock;

    public UpdateObligations(
            UpdateObligationRepository obligations,
            PlatformStaff staff,
            AuditLog audit,
            ObligationProperties properties,
            Clock clock) {

        this.obligations = obligations;
        this.staff = staff;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Opens a clock for a campaign that closed above its goal.
     *
     * <p><strong>Idempotent, because the outbox redelivers.</strong> {@code OutboxMessage} states
     * that as the contract rather than as a caveat, and the natural key here is the campaign — so
     * a second delivery of {@code project.succeeded} finds the row and leaves it alone. Restarting
     * the clock on a redelivery would forgive a lapse that a redelivery has nothing to say about.
     *
     * @return true when this call opened it
     */
    @Transactional
    public boolean open(UUID projectId, UUID creatorId, Instant closedAt) {
        if (obligations.existsById(projectId)) {
            return false;
        }
        obligations.save(new UpdateObligation(
                projectId, creatorId, closedAt.truncatedTo(ChronoUnit.MICROS), properties.interval()));
        log.debug("Opened an update obligation for campaign {}", projectId);
        return true;
    }

    /**
     * Records an update and starts the next month.
     *
     * <p>Does nothing for a campaign with no obligation, which is the ordinary case: most updates
     * are published while a campaign is live, and §5.5's clock only exists after a campaign has
     * closed above goal.
     *
     * <p><strong>A scheduled update is credited from the instant it becomes visible, which may be
     * in the future.</strong> {@code ProjectUpdatePublishedEvent} is recorded when the row is
     * written rather than when it publishes, and its own comment already accepts that asymmetry
     * for cache invalidation. The consequence here is that a creator who schedules three monthly
     * updates in advance is up to date for three months — which is odd and is also, on the
     * merits, three updates their backers will receive. Closing it means re-announcing at
     * publication time, which is a change to that event and not to this module.
     */
    @Transactional
    public boolean recordUpdate(UUID projectId, Instant publishedAt) {
        return obligations
                .findById(projectId)
                .map(obligation ->
                        obligation.recordUpdate(publishedAt.truncatedTo(ChronoUnit.MICROS), properties.interval()))
                .orElse(false);
    }

    /**
     * Stops the clock because fulfilment is complete.
     *
     * <p>#437 asks that "fulfilment completing stops it", and this is that call. It is not wired
     * to anything automatic yet: what counts as complete for a campaign with digital rewards, no
     * rewards, or a partial shipment is §4.8's question and {@code fulfilments} does not answer
     * it in one column. Until it does, the honest arrangement is a creator or a moderator saying
     * so — and a clock that keeps running is the fail-safe direction, because it keeps asking for
     * updates rather than silently deciding the creator is finished.
     */
    @Transactional
    public boolean close(UUID projectId, Instant at) {
        return obligations
                .findById(projectId)
                .map(obligation -> obligation.close(at.truncatedTo(ChronoUnit.MICROS)))
                .orElse(false);
    }

    /** One campaign's state, as its own page draws it. */
    @Transactional(readOnly = true)
    public Optional<ObligationView> forProject(UUID projectId) {
        return obligations.findById(projectId).map(this::view);
    }

    /**
     * Every campaign this creator has run, as the profile draws it.
     *
     * <p>§22.3's "the creator's project history visible", which #439 surfaces. The list is the
     * consequence #437 is built around: a creator whose last campaign went eight months without
     * an update, shown on the page where they are asking for money again.
     */
    @Transactional(readOnly = true)
    public List<ObligationView> forCreator(UUID creatorId) {
        return obligations.forCreator(creatorId).stream().map(this::view).toList();
    }

    /** Lapses no moderator has closed, oldest first. AD-01's queue, one row per campaign. */
    @Transactional(readOnly = true)
    public List<ObligationView> escalated(UUID staffId, int limit) {
        staff.requireCapability(staffId, StaffCapability.MODERATE_CONTENT);
        return obligations.escalated(Limit.of(limit)).stream().map(this::view).toList();
    }

    /**
     * Closes an escalation, with a note saying what the moderator did about it.
     *
     * <p><strong>This closes the case and not the obligation.</strong> The clock keeps running,
     * and a creator who lapses again is escalated again — which is why the note is required. The
     * next person to open this campaign's file has to be able to tell "spoke to them" from "they
     * had already posted", and a resolution nobody explained is one nobody can rely on.
     *
     * @throws NoLapseToResolveException when the campaign has no open escalation
     */
    @Transactional
    public ObligationView resolve(UUID staffId, UUID projectId, String note) {
        staff.requireCapability(staffId, StaffCapability.MODERATE_CONTENT);

        UpdateObligation obligation =
                obligations.findById(projectId).orElseThrow(() -> new NoLapseToResolveException(projectId));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!obligation.resolve(staffId, note, now)) {
            throw new NoLapseToResolveException(projectId);
        }
        obligations.save(obligation);

        audit.record(
                AuditAction.UPDATE_OBLIGATION_RESOLVED,
                projectId,
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                "lapsedAt=%s; dueAt=%s".formatted(obligation.lapsedAt(), obligation.dueAt()));

        log.info("Update obligation escalation on campaign {} resolved by {}", projectId, staffId);
        return view(obligation);
    }

    private ObligationView view(UpdateObligation obligation) {
        return new ObligationView(
                obligation.projectId(),
                obligation.creatorId(),
                obligation.stateAt(clock.instant(), properties.reminderLead()),
                obligation.openedAt(),
                obligation.lastUpdateAt(),
                obligation.dueAt(),
                obligation.lapsedAt(),
                obligation.resolvedAt(),
                obligation.resolutionNote(),
                obligation.closedAt());
    }

    /**
     * What a reader is told, with the state already worked out.
     *
     * <p>A record rather than the entity, so that a controller cannot hold a managed instance and
     * so that {@link ObligationState} is computed once, here, against one instant. A client given
     * the four timestamps and asked to work out the state is a client that gets the boundary
     * wrong, and the second client to do it gets it wrong differently.
     */
    public record ObligationView(
            UUID projectId,
            UUID creatorId,
            ObligationState state,
            Instant openedAt,
            Instant lastUpdateAt,
            Instant dueAt,
            Instant lapsedAt,
            Instant resolvedAt,
            String resolutionNote,
            Instant closedAt) {
    }
}
