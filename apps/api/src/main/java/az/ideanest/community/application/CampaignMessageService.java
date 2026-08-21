package az.ideanest.community.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.community.CommunityProperties;
import az.ideanest.community.domain.CampaignMessage;
import az.ideanest.community.infrastructure.CampaignMessageRepository;
import az.ideanest.pledge.application.BackerSegment;
import az.ideanest.pledge.application.BackerSegmentService;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.access.ProjectAuthorisation;
import az.ideanest.shared.access.ProjectCapability;
import az.ideanest.shared.audience.AudienceProperties;
import az.ideanest.shared.audience.ProjectAudience;
import az.ideanest.shared.audience.ProjectAudiences;
import az.ideanest.shared.audience.SegmentAudience;
import az.ideanest.shared.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.7's CD-13 (#98): a creator messaging their backers, or a saved segment of them.
 *
 * <h2>Two capabilities, and which one depends on the audience</h2>
 *
 * <p>{@link ProjectCapability#PUBLISH_UPDATES} is always required: this speaks to backers in the
 * campaign's name, which is the same authority a numbered update needs and the reason #236 made
 * that capability separate from "may edit this campaign at all".
 *
 * <p>{@link ProjectCapability#VIEW_FINANCES} is required <strong>only when a segment is
 * named</strong>, and the asymmetry is deliberate rather than an oversight. Messaging every
 * backer needs no knowledge of who they are — the audience is "everybody", which reveals
 * nothing. Choosing a segment is an act of the backer report: it selects people by state, by
 * reward tier, by country or by a search over their names and addresses, and a collaborator who
 * may not read that report should not be able to interrogate it by sending messages and watching
 * the recipient counts. The check arrives through {@code BackerSegmentService}, which enforces
 * it on every read of a segment; this service does not repeat it, for the reason
 * {@code BackerExportService} gives — two answers to a question {@code ProjectAccess} exists to
 * answer once, and the one that matters is the one nearest the data.
 *
 * <h2>The audience is resolved here and again at delivery</h2>
 *
 * <p>Once here, to freeze {@code recipient_count} and tell the creator what they sent to; once in
 * the notification module, to write the rows. {@link CampaignMessageSentEvent} argues why the two
 * are allowed to differ and why making them agree would mean storing a membership list — which is
 * the one thing V31 refuses.
 *
 * <p>Both resolutions go through a published port. This module may not read {@code pledges} or
 * {@code backer_segments}; {@code ProjectAudiences} answers "who backed this campaign" and
 * {@code SegmentAudience} answers "who is in this segment", and both live in the pledge module
 * where the rows are.
 *
 * <h2>Rate limiting is not here, and the audit trail is</h2>
 *
 * <p>The limit is the controller's, following {@code CommentController}: it is about the
 * transport. The audit row is this service's, because it is about the act — and it is written in
 * the same transaction as the message and the event, unlike {@code BackerExportService}'s, which
 * has no transaction to join and uses {@code recordIndependently} instead.
 *
 * <p><strong>The audit row records the act and never the content.</strong> Who messaged which
 * segment, how many people it reached, and whether the ceiling truncated it. Not the subject and
 * not the body: {@code audit_logs} is the one table with no retention rule and refuses
 * {@code DELETE}, so putting creator prose in it is a decision nobody can take back.
 */
@Service
public class CampaignMessageService {

    private static final Logger log = LoggerFactory.getLogger(CampaignMessageService.class);

    private final CampaignMessageRepository messages;
    private final BackerSegmentService segments;
    private final ProjectAuthorisation projects;
    private final ProjectAudiences audiences;
    private final SegmentAudience segmentAudience;
    private final Outbox outbox;
    private final AuditLog audit;
    private final CommunityProperties properties;
    private final AudienceProperties audienceBound;
    private final Clock clock;

    public CampaignMessageService(
            CampaignMessageRepository messages,
            BackerSegmentService segments,
            ProjectAuthorisation projects,
            ProjectAudiences audiences,
            SegmentAudience segmentAudience,
            Outbox outbox,
            AuditLog audit,
            CommunityProperties properties,
            AudienceProperties audienceBound,
            Clock clock) {

        this.messages = messages;
        this.segments = segments;
        this.projects = projects;
        this.audiences = audiences;
        this.segmentAudience = segmentAudience;
        this.outbox = outbox;
        this.audit = audit;
        this.properties = properties;
        this.audienceBound = audienceBound;
        this.clock = clock;
    }

    /**
     * Sends a message to a campaign's backers, or to a saved segment of them.
     *
     * @param segmentId the saved segment, or null for every backer
     * @param senderId the authenticated caller, never a value from a request body
     * @throws az.ideanest.project.application.ProjectNotFoundException for a campaign that does
     *     not exist and for one this account has no part in, identically
     * @throws az.ideanest.project.application.CapabilityNotGrantedException without
     *     {@code PUBLISH_UPDATES}, and without {@code VIEW_FINANCES} when a segment is named
     * @throws az.ideanest.pledge.application.BackerSegmentNotFoundException when
     *     {@code segmentId} names no segment on this campaign
     * @throws az.ideanest.community.domain.MessageContentInvalidException when the subject or
     *     body is empty or too long
     */
    @Transactional
    public CampaignMessage send(UUID projectId, UUID senderId, UUID segmentId, String subject, String body) {
        projects.requireCapability(projectId, senderId, ProjectCapability.PUBLISH_UPDATES);

        // Resolved before anything is written, so a message to a segment that does not exist is
        // refused rather than recorded as having reached nobody.
        BackerSegment segment = segmentId == null ? null : segments.segmentOf(projectId, senderId, segmentId);

        // One more than the ceiling, so "there were more" is a fact rather than an inference
        // from a full page -- the same trick NotificationEventListener uses, and the reason
        // `truncated` can be honest.
        int ceiling = audienceBound.maxRecipients();
        List<UUID> recipients = segment == null
                ? audiences.membersOf(projectId, ProjectAudience.BACKERS, ceiling + 1)
                : segmentAudience.membersOf(projectId, segment.id(), ceiling + 1);

        boolean truncated = recipients.size() > ceiling;
        int reached = truncated ? ceiling : recipients.size();

        if (truncated) {
            // The same ERROR the fan-out logs, and for the same reason: the people beyond the
            // ceiling are people the platform decided not to tell, and a creator reading
            // `truncated` in the response deserves an operator who can see why.
            log.error(
                    "Campaign {} has more than {} backers in the audience for message from {}; the message reaches"
                            + " the first {} of them and the rest are not told",
                    projectId,
                    ceiling,
                    senderId,
                    ceiling);
        }

        CampaignMessage message = messages.saveAndFlush(CampaignMessage.sent(
                Identifiers.newIdentifier(),
                projectId,
                segment == null ? null : segment.id(),
                segment == null ? null : segment.name(),
                senderId,
                subject,
                body,
                reached,
                truncated));

        Instant sentAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID eventId = outbox.record(
                CampaignMessageSentEvent.AGGREGATE_TYPE,
                projectId,
                CampaignMessageSentEvent.EVENT_TYPE,
                CampaignMessageSentEvent.of(message, sentAt));

        audit.record(
                AuditAction.PROJECT_SEGMENT_MESSAGED,
                projectId,
                AuditActor.user(senderId),
                AuditOutcome.SUCCEEDED,
                detailOf(message));

        log.debug(
                "Campaign {} messaged {} backers; message {}, outbox event {}.",
                projectId,
                reached,
                message.getId(),
                eventId);
        return message;
    }

    /**
     * What this campaign has sent, newest first.
     *
     * <p>{@code PUBLISH_UPDATES} rather than {@code VIEW_FINANCES}, even for messages that went
     * to a segment: what comes back is the campaign's own outgoing post, and the segment appears
     * as the name it had. A name is not the report — it does not say who matched it, and it was
     * chosen by somebody on the same team.
     */
    @Transactional(readOnly = true)
    public CampaignMessagePage sent(UUID projectId, UUID accountId, SignalCursor cursor, Integer requestedSize) {
        projects.requireCapability(projectId, accountId, ProjectCapability.PUBLISH_UPDATES);

        int size = properties.messages().pageSize(requestedSize);
        PageRequest limit = PageRequest.ofSize(size + 1);
        List<CampaignMessage> rows = cursor == null
                ? messages.page(projectId, limit)
                : messages.pageBefore(projectId, cursor.at(), cursor.id(), limit);

        boolean more = rows.size() > size;
        List<CampaignMessage> page = more ? rows.subList(0, size) : rows;
        CampaignMessage last = page.isEmpty() ? null : page.get(page.size() - 1);

        return new CampaignMessagePage(
                page, more && last != null ? new SignalCursor(last.getCreatedAt(), last.getId()) : null);
    }

    /**
     * The audit detail: the act, never the content.
     *
     * <p>The segment by name as well as by identifier, because the support conversation that
     * reads this row is held in names — and because the segment may since have been deleted, at
     * which point the identifier resolves to nothing and the name is all there is.
     */
    private static String detailOf(CampaignMessage message) {
        return "audience=" + (message.isTargeted() ? "segment:" + message.getSegmentId() : "all-backers")
                + (message.isTargeted() ? "; segmentName=" + message.getSegmentName() : "")
                + "; recipients=" + message.getRecipientCount()
                + "; truncated=" + message.isTruncated()
                + "; messageId=" + message.getId();
    }
}
