package az.ideanest.compliance.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.compliance.domain.CampaignLegalSubject;
import az.ideanest.compliance.domain.CreatorLegalSubject;
import az.ideanest.compliance.infrastructure.CampaignLegalSubjectRepository;
import az.ideanest.compliance.infrastructure.CreatorLegalSubjectRepository;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import az.ideanest.shared.compliance.LegalSubject;
import az.ideanest.shared.compliance.LegalSubjects;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The creator's legal subject: recorded, read, and frozen at submission — issue #430.
 *
 * <p>The answerer behind {@link LegalSubjects}, which is what the project and payout modules
 * name. Everything they may ask is on that interface; the rest of this class is what only the
 * owner of the row and a member of staff may do.
 *
 * <h2>What is deliberately not enforced here</h2>
 *
 * <p><strong>A creator may edit their subject while a campaign of theirs is submitted.</strong>
 * #430 says the subject is entered "while no campaign is submitted", and it would be
 * reasonable to read that as a lock. It is not implemented as one, for two reasons.
 *
 * <p>The first is that the freeze already provides what the lock was for. A submitted campaign
 * carries its own copy; editing the live row cannot reach it, which is the property the lock
 * would have been protecting and it is protected whether or not anybody edits.
 *
 * <p>The second is that the lock cannot be built here without a cycle. Asking "does this
 * creator have a submitted campaign" means asking the project module, and the project module
 * already asks this one to freeze a subject at submission. {@code ModuleBoundaryTests} checks
 * for cycles and would fail; more to the point, a cycle between the module that owns campaigns
 * and the module that owns compliance facts is the wrong shape regardless of what a test says.
 *
 * <p>What is lost is small and is worth stating: a creator can correct a typo in their legal
 * name while a reviewer is reading their campaign, and the reviewer sees the old name because
 * they are reading the frozen copy. That is the correct behaviour — the reviewer is deciding
 * the submission that was made — and it is not what the sentence in #430 was worried about.
 */
@Service
public class CreatorLegalSubjects implements LegalSubjects {

    private static final Logger log = LoggerFactory.getLogger(CreatorLegalSubjects.class);

    private final CreatorLegalSubjectRepository subjects;
    private final CampaignLegalSubjectRepository snapshots;
    private final PlatformStaff staff;
    private final AuditLog audit;
    private final Clock clock;

    public CreatorLegalSubjects(
            CreatorLegalSubjectRepository subjects,
            CampaignLegalSubjectRepository snapshots,
            PlatformStaff staff,
            AuditLog audit,
            Clock clock) {
        this.subjects = subjects;
        this.snapshots = snapshots;
        this.staff = staff;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LegalSubject> of(UUID accountId) {
        return subjects.findById(accountId).map(CreatorLegalSubject::asLegalSubject);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LegalSubject> frozenFor(UUID projectId) {
        return snapshots.findById(projectId).map(CampaignLegalSubject::asLegalSubject);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code MANDATORY}: the freeze is part of the submission, not a thing that happens near
     * it. A campaign that reached {@code SUBMITTED} without its subject having been frozen —
     * because the freeze committed separately and the submission then failed, or the reverse —
     * is the inconsistency the whole snapshot exists to avoid.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void freezeFor(UUID projectId, UUID accountId) {
        Optional<CreatorLegalSubject> recorded = subjects.findById(accountId);
        if (recorded.isEmpty()) {
            log.debug(
                    "Campaign {} submitted by {} with no legal subject recorded; nothing frozen. "
                            + "Until #424 sets a threshold this is the ordinary case.",
                    projectId,
                    accountId);
            return;
        }
        LegalSubject subject = recorded.get().asLegalSubject();
        Instant now = clock.instant();
        CampaignLegalSubject snapshot = snapshots
                .findById(projectId)
                .map(existing -> {
                    existing.freeze(accountId, subject, now);
                    return existing;
                })
                .orElseGet(() -> CampaignLegalSubject.of(projectId, accountId, subject, now));
        snapshots.save(snapshot);
        log.info("Campaign {} frozen against {} legal subject of {}", projectId, subject.subjectKind(), accountId);
    }

    /**
     * What the creator has recorded, for their own settings screen.
     *
     * <p>The row rather than {@link LegalSubject}, so the screen can say when it was last
     * changed. {@code of} is the shared contract and carries only the fact; a creator looking at
     * their own settings is entitled to the timestamp as well, and a response whose
     * {@code updatedAt} was null on a read and populated on a write would be a field the client
     * learns not to trust.
     */
    @Transactional(readOnly = true)
    public Optional<CreatorLegalSubject> mine(UUID accountId) {
        return subjects.findById(accountId);
    }

    /**
     * Record or replace what the creator says they are.
     *
     * <p>Audited. The subject decides withholding, decides which party an agreement binds, and
     * is the name every later match is against, so "when did this change and to what" is a
     * question a dispute starts from. The detail carries the kind and the name; the address and
     * the registration number are on the row and do not need a second copy in the log.
     */
    @Transactional
    public LegalSubject record(UUID accountId, LegalSubject subject) {
        Instant now = clock.instant();
        Optional<CreatorLegalSubject> existing = subjects.findById(accountId);
        CreatorLegalSubject row = existing
                .map(found -> {
                    found.apply(subject, now);
                    return found;
                })
                .orElseGet(() -> CreatorLegalSubject.of(accountId, subject, now));
        CreatorLegalSubject saved = subjects.save(row);
        audit.record(
                AuditAction.LEGAL_SUBJECT_RECORDED,
                accountId,
                AuditActor.user(accountId),
                AuditOutcome.SUCCEEDED,
                "kind=%s; name=%s; first=%s; complete=%s"
                        .formatted(
                                subject.subjectKind(),
                                subject.legalName(),
                                existing.isEmpty(),
                                subject.isComplete()));
        log.info("Account {} recorded a {} legal subject", accountId, subject.subjectKind());
        return saved.asLegalSubject();
    }

    /**
     * A member of staff reads somebody's legal subject.
     *
     * <p>{@code ACCEPTANCE_RECORD_READ}'s argument, unchanged: a read of somebody's own record
     * rather than of the platform's is one whose use should be answerable, it is cheap to
     * record, and the absence is noticed only during the investigation that needed it.
     *
     * <p>{@code REVIEW_IDENTITY_VERIFICATION} rather than a capability of its own. #436 named
     * the capability for the queue this screen sits beside, and a reviewer who may open an
     * identity document may certainly read the name on the account it belongs to. A separate
     * capability that is granted to exactly the same people is a role nobody maintains.
     *
     * <p><strong>Not {@code readOnly}, and it reads like a mistake until it does not.</strong>
     * The method's own work is two selects; the audit row is an insert, {@code MANDATORY} into
     * this very transaction, and PostgreSQL refuses an insert in a read-only one. A read that
     * records the read is a write, and marking it otherwise turns the audit requirement into a
     * 500 the first time somebody opens the screen.
     */
    @Transactional
    public StaffView forStaff(UUID staffId, UUID accountId) {
        staff.requireCapability(staffId, StaffCapability.REVIEW_IDENTITY_VERIFICATION);
        Optional<CreatorLegalSubject> row = subjects.findById(accountId);
        List<CampaignLegalSubject> frozen = snapshots.findByUserIdOrderByFrozenAtDesc(accountId);
        audit.record(
                AuditAction.LEGAL_SUBJECT_READ,
                accountId,
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                "recorded=%s; snapshots=%d".formatted(row.isPresent(), frozen.size()));
        return new StaffView(row.orElse(null), frozen);
    }

    /**
     * What the console draws: the live row, and every campaign frozen against it.
     *
     * <p>Both, because the screen's job is to make a divergence visible. A creator whose live
     * subject is a company and whose funded campaign was submitted as an individual is not an
     * error, and it is exactly the thing a finance operator needs to see before a payout.
     */
    public record StaffView(CreatorLegalSubject recorded, List<CampaignLegalSubject> campaigns) {

        public Optional<CreatorLegalSubject> subject() {
            return Optional.ofNullable(recorded);
        }
    }
}
