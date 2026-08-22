package az.ideanest.pledgemanager.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.pledge.application.BackedPledges;
import az.ideanest.pledgemanager.PledgeManagerProperties;
import az.ideanest.pledgemanager.domain.Fulfilment;
import az.ideanest.pledgemanager.domain.FulfilmentStatus;
import az.ideanest.pledgemanager.domain.Tracking;
import az.ideanest.pledgemanager.domain.TrackingInvalidException;
import az.ideanest.pledgemanager.infrastructure.FulfilmentRepository;
import az.ideanest.shared.access.ProjectAuthorisation;
import az.ideanest.shared.access.ProjectCapability;
import az.ideanest.shared.project.ProjectSummaries;
import az.ideanest.shared.project.ProjectSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.8's PM-20 to PM-22 (#80): the creator's tracking import, and both sides reading
 * what it produced.
 *
 * <h2>Two actors again, and the asymmetry is the point</h2>
 *
 * <p>The <strong>creator</strong> writes every row and reads the campaign's list; the
 * <strong>backer</strong> reads exactly the rows for their own pledges and writes
 * nothing. A backer confirming receipt would be a second source of truth about the
 * same parcel and the two would disagree in the case that matters — a delivery the
 * carrier recorded and the backer never received.
 *
 * <p>The creator's side asks for {@link ProjectCapability#VIEW_FINANCES}, which is the
 * same capability {@code ShippingAddressService} requires and for the same reason: a
 * list of pledges with a destination and a parcel against each is the backer report
 * with the fulfilment columns added. {@code EDIT_REWARDS} was the alternative and is
 * wrong — it governs what a backer is <em>promised</em>, and this is a record of what
 * they were <em>sent</em>.
 *
 * <h2>Why a whole file rather than a row at a time</h2>
 *
 * <p>{@link TrackingCsv} carries that argument. What belongs here is what happens to
 * the rows once they are read: each is applied on its own, a row that cannot be
 * applied is reported with its line number, and the rest of the file still lands. The
 * alternative — one bad row fails the upload — is a creator with four thousand parcels
 * and three typos being sent away with nothing, twice, because the second attempt
 * fails on the typo they did not find the first time.
 *
 * <h2>What this does not do</h2>
 *
 * <p><strong>It notifies nobody.</strong> "Your reward has shipped" is a notification
 * §4.10 does not have a type for, and inventing one here would mean a bulk import
 * fanning out four thousand emails from inside a request. The backer's list is a pull,
 * PM-21 asks for exactly that, and the push belongs with the epic that owns
 * notification types.
 *
 * <p><strong>It does not touch the pledge.</strong> §6.2 has a {@code FULFILLED}
 * pledge state and this never sets it: a pledge's state is about money, the last
 * transition into it is {@code COLLECTED → FULFILLED}, and no pledge on this platform
 * has ever been collected because collection is epic #59. Marking a parcel delivered
 * would otherwise move a pledge from {@code CONFIRMED} to {@code FULFILLED} and skip
 * the charge entirely.
 */
@Service
public class FulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentService.class);

    private final FulfilmentRepository fulfilments;
    private final BackedPledges pledges;
    private final ProjectAuthorisation projects;
    private final ProjectSummaries campaigns;
    private final AuditLog audit;
    private final PledgeManagerProperties properties;
    private final Clock clock;

    public FulfilmentService(
            FulfilmentRepository fulfilments,
            BackedPledges pledges,
            ProjectAuthorisation projects,
            ProjectSummaries campaigns,
            AuditLog audit,
            PledgeManagerProperties properties,
            Clock clock) {

        this.fulfilments = fulfilments;
        this.pledges = pledges;
        this.projects = projects;
        this.campaigns = campaigns;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * PM-20: applies a tracking file to a campaign's parcels.
     *
     * <p>One transaction for the whole file. A creator who uploads four thousand rows
     * and loses the connection has either all of them or none, which is the only
     * outcome they can act on — a partial import would leave them unable to tell which
     * half to send again. The rows this refuses are a different thing entirely: they
     * were never going to be written, and they are reported.
     *
     * @param document the file as it arrived, {@code text/csv}
     * @throws FulfilmentImportRejectedException when the document itself is unusable
     * @throws az.ideanest.project.application.ProjectNotFoundException for a campaign
     *     that does not exist and for one this account has no part in, identically
     * @throws az.ideanest.project.application.CapabilityNotGrantedException without
     *     {@code VIEW_FINANCES}
     */
    @Transactional
    public FulfilmentImport importTracking(UUID projectId, UUID accountId, String document) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        int rowCap = properties.fulfilment().importRowCap();
        TrackingCsv.Parsed parsed = TrackingCsv.parse(document, rowCap);

        // One read of the campaign's backings rather than one per row. Bounded by the
        // same cap as the file, which is what makes both halves of the comparison the
        // same size: a campaign with more backers than the cap cannot import them all
        // in one file, and `truncated` is how it is told so.
        Map<UUID, BackedPledges.BackedPledge> backings = new LinkedHashMap<>();
        for (BackedPledges.BackedPledge pledge : pledges.onProject(projectId, rowCap)) {
            backings.put(pledge.pledgeId(), pledge);
        }

        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<FulfilmentImport.RowFailure> failures = new ArrayList<>();
        Set<UUID> applied = new HashSet<>();
        int changed = 0;
        int unchanged = 0;
        int maxReported = properties.fulfilment().maxReportedErrors();

        for (TrackingCsv.Row row : parsed.rows()) {
            RowOutcome outcome = apply(row, projectId, backings, applied, accountId, at);
            if (outcome.failure() != null) {
                if (failures.size() < maxReported) {
                    failures.add(outcome.failure());
                }
            } else if (outcome.changed()) {
                changed++;
            } else {
                unchanged++;
            }
        }

        int failed = parsed.rows().size() - changed - unchanged;

        // Recorded inside the transaction, like every other privileged write in this
        // module: an import that rolled back must not leave an audit row claiming it
        // happened. The detail is counts and never a tracking number -- `audit_logs`
        // has no retention rule, and a parcel's whereabouts is not something to put in
        // a table nothing can delete from.
        audit.record(
                AuditAction.PROJECT_FULFILMENTS_IMPORTED,
                projectId,
                AuditActor.user(accountId),
                // SUCCEEDED even when rows were refused: the import ran and wrote what it
                // could, which is what happened. REFUSED is for the whole request being
                // turned away, and reading it here would make an audit search for refused
                // privileged actions return every creator with a typo in a spreadsheet.
                AuditOutcome.SUCCEEDED,
                "rows=%d; changed=%d; unchanged=%d; failed=%d; truncated=%s"
                        .formatted(parsed.rows().size(), changed, unchanged, failed, parsed.truncated()));

        log.info(
                "Campaign {} imported {} tracking rows: {} changed, {} unchanged, {} failed",
                projectId,
                parsed.rows().size(),
                changed,
                unchanged,
                failed);

        return new FulfilmentImport(
                parsed.rows().size(), changed, unchanged, failed, failures, parsed.truncated());
    }

    /**
     * What one row did: nothing, because it was refused, or a write that either altered
     * the parcel or restated it.
     *
     * <p>A value rather than a field on the service, which is what this was first
     * written as. A service is a singleton and two creators import at the same time;
     * anything a row leaves behind on the instance is a count belonging to somebody
     * else's campaign.
     */
    private record RowOutcome(FulfilmentImport.RowFailure failure, boolean changed) {

        static RowOutcome refused(FulfilmentImport.RowFailure failure) {
            return new RowOutcome(failure, false);
        }

        static RowOutcome written(boolean changed) {
            return new RowOutcome(null, changed);
        }
    }

    private RowOutcome apply(
            TrackingCsv.Row row,
            UUID projectId,
            Map<UUID, BackedPledges.BackedPledge> backings,
            Set<UUID> applied,
            UUID accountId,
            Instant at) {

        UUID pledgeId;
        try {
            pledgeId = UUID.fromString(row.pledgeId() == null ? "" : row.pledgeId());
        } catch (IllegalArgumentException notAnIdentifier) {
            return RowOutcome.refused(new FulfilmentImport.RowFailure(
                    row.line(),
                    row.pledgeId(),
                    "FULFILMENT_ROW_PLEDGE_INVALID",
                    "This is not a pledge identifier. Copy the pledge_id column from the backer export."));
        }

        if (!backings.containsKey(pledgeId)) {
            // One answer for three cases: no such pledge, a pledge on another
            // campaign, and a pledge that is not a backing -- an expired reservation
            // or a cancellation. A creator cannot act differently on any of them and
            // distinguishing them would tell whoever holds the file which identifiers
            // exist elsewhere on the platform.
            return RowOutcome.refused(new FulfilmentImport.RowFailure(
                    row.line(),
                    row.pledgeId(),
                    "FULFILMENT_ROW_PLEDGE_NOT_BACKING",
                    "This campaign has no such backing. It may have been cancelled since the export."));
        }

        if (!applied.add(pledgeId)) {
            // The same pledge twice in one file is two claims about one parcel, and
            // last-one-wins would silently pick one of them. The creator has a
            // duplicated row in a spreadsheet and needs to know which line.
            return RowOutcome.refused(new FulfilmentImport.RowFailure(
                    row.line(),
                    row.pledgeId(),
                    "FULFILMENT_ROW_DUPLICATE",
                    "This pledge appears more than once in the file. Keep one row per parcel."));
        }

        Optional<FulfilmentStatus> status = statusOf(row);
        if (status.isEmpty()) {
            return RowOutcome.refused(new FulfilmentImport.RowFailure(
                    row.line(),
                    row.pledgeId(),
                    row.status() == null ? "FULFILMENT_ROW_STATUS_MISSING" : "FULFILMENT_ROW_STATUS_UNKNOWN",
                    "Status must be one of PREPARING, SHIPPED, DELIVERED or RETURNED."));
        }

        Tracking tracking;
        try {
            tracking = new Tracking(row.carrier(), row.trackingNumber(), row.trackingUrl());
        } catch (TrackingInvalidException invalid) {
            return RowOutcome.refused(new FulfilmentImport.RowFailure(
                    row.line(), row.pledgeId(), "FULFILMENT_ROW_TRACKING_INVALID", invalid.getMessage()));
        }

        Optional<Fulfilment> existing = fulfilments.findById(pledgeId);
        if (existing.isPresent()) {
            return RowOutcome.written(existing.get().apply(status.get(), tracking, accountId, at));
        }
        fulfilments.save(Fulfilment.of(pledgeId, projectId, status.get(), tracking, accountId, at));
        return RowOutcome.written(true);
    }

    /**
     * The status a row means.
     *
     * <p><strong>A blank status with a tracking number is {@code SHIPPED}.</strong>
     * The file a fulfilment partner sends back has a tracking column and frequently no
     * status column at all, and refusing it would make the common case the one that
     * needs a spreadsheet edit. A blank status with nothing else on the row means the
     * creator has told the platform nothing, which is not something to guess at.
     */
    private static Optional<FulfilmentStatus> statusOf(TrackingCsv.Row row) {
        if (row.status() != null) {
            return FulfilmentStatus.parse(row.status());
        }
        return row.trackingNumber() == null ? Optional.empty() : Optional.of(FulfilmentStatus.SHIPPED);
    }

    /**
     * PM-22, the creator's half: every parcel on the campaign.
     *
     * <p>Unpaged, bounded by the same thing the import is bounded by — a campaign has
     * as many parcels as it has backers. It is a fulfilment working list rather than a
     * screen a visitor loads, and paging it would mean a creator cross-referencing a
     * spreadsheet against six pages.
     */
    @Transactional(readOnly = true)
    public List<Fulfilment> ofCampaign(UUID projectId, UUID accountId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);
        return fulfilments.findByProject(projectId);
    }

    /**
     * The counts a creator watches, without reading a single parcel.
     *
     * <p>{@code untouched} is the one that matters and the one that needs the backings:
     * a campaign that has imported nothing has four thousand parcels nobody has said
     * anything about, and a progress view built only from this table would report it as
     * finished.
     */
    @Transactional(readOnly = true)
    public FulfilmentProgress progressOf(UUID projectId, UUID accountId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        Map<FulfilmentStatus, Long> counts = new EnumMap<>(FulfilmentStatus.class);
        for (Object[] row : fulfilments.countByStatus(projectId)) {
            counts.put((FulfilmentStatus) row[0], (Long) row[1]);
        }
        long recorded = counts.values().stream().mapToLong(Long::longValue).sum();
        long backings = pledges.onProject(projectId, properties.fulfilment().importRowCap())
                .size();

        return new FulfilmentProgress(
                backings,
                counts.getOrDefault(FulfilmentStatus.PREPARING, 0L),
                counts.getOrDefault(FulfilmentStatus.SHIPPED, 0L),
                counts.getOrDefault(FulfilmentStatus.DELIVERED, 0L),
                counts.getOrDefault(FulfilmentStatus.RETURNED, 0L),
                Math.max(0, backings - recorded));
    }

    /**
     * PM-21: what one backer can see about their own parcels, across every campaign.
     *
     * <p>Built from {@code BackedPledges.ofBacker} rather than from a backer column on
     * this table, which is why there is not one: the pledge already knows whose it is,
     * and a copy here would be a second answer able to disagree with it after §17.4
     * anonymises an account.
     *
     * <p>A pledge with no row is absent rather than reported as {@code PREPARING} —
     * {@link BackerFulfilment} says why the platform must not make a claim the creator
     * has not made.
     */
    @Transactional(readOnly = true)
    public List<BackerFulfilment> ofBacker(UUID backerId) {
        Map<UUID, UUID> campaignOf = new LinkedHashMap<>();
        for (BackedPledges.BackedPledge pledge : pledges.ofBacker(backerId)) {
            campaignOf.put(pledge.pledgeId(), pledge.projectId());
        }
        if (campaignOf.isEmpty()) {
            return List.of();
        }

        List<Fulfilment> rows = fulfilments.findByPledges(campaignOf.keySet());
        Map<UUID, ProjectSummary> summaries = new LinkedHashMap<>();
        for (ProjectSummary summary : campaigns.summariesOf(rows.stream()
                .map(Fulfilment::getProjectId)
                .distinct()
                .toList())) {
            summaries.put(summary.id(), summary);
        }

        return rows.stream()
                .map(row -> new BackerFulfilment(summaries.get(row.getProjectId()), row))
                .toList();
    }
}
