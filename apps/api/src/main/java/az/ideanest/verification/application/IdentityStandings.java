package az.ideanest.verification.application;

import az.ideanest.compliance.application.ComplianceOverrides;
import az.ideanest.shared.compliance.ComplianceRequirement;
import az.ideanest.shared.compliance.CreatorStandings;
import az.ideanest.shared.compliance.LegalSubject;
import az.ideanest.shared.compliance.LegalSubjects;
import az.ideanest.shared.compliance.SubjectKind;
import az.ideanest.shared.compliance.VerificationStanding;
import az.ideanest.verification.VerificationProperties;
import az.ideanest.verification.domain.IdentityVerification;
import az.ideanest.verification.infrastructure.IdentityVerificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a creator stands with identity verification — issue #431's answerer.
 *
 * <p>Three sources fold into one value here and nowhere else: the verification row,
 * {@code ideanest.verification.required}, and #436's override. A caller that consulted all
 * three would be a second copy of the rule, and the second copy is the one that gets a case
 * wrong — most likely the expiry case, which is the one that costs a creator money.
 *
 * <h2>The flag is off, and this class ships anyway</h2>
 *
 * <p>V58's header set out the shape and #431 completes it:
 *
 * <blockquote>
 * So verification is requestable and recordable, nothing is blocked on it, and
 * {@code ideanest.verification.required} exists so the day the answer arrives is a
 * configuration change and a wiring change rather than a migration.
 * </blockquote>
 *
 * <p>This is the wiring change. The answer — #424's threshold, which waits on #423's
 * anti-money-laundering row — is not here, and inventing one would be "the position a
 * regulator reads back to us". With the flag off every creator is {@link
 * VerificationStanding#NOT_REQUIRED}, every payout releases, and the mechanism is exercised by
 * its tests rather than by production. Turning it on is one line of configuration.
 *
 * <h2>Expiry is read, not swept</h2>
 *
 * <p>An approval whose {@code expires_at} has passed reads as {@link
 * VerificationStanding#EXPIRED} here whether or not any job has moved the row's state yet. The
 * comparison is the authority and the sweep is housekeeping, for {@code ComplianceOverride}'s
 * reason: a gate that depended on a job having run is a gate that opens for as long as the job
 * is broken.
 */
@Service
public class IdentityStandings implements CreatorStandings {

    private static final Logger log = LoggerFactory.getLogger(IdentityStandings.class);

    private final IdentityVerificationRepository verifications;
    private final IdentityVerifications identities;
    private final ComplianceOverrides overrides;
    private final LegalSubjects legalSubjects;
    private final VerificationProperties properties;
    private final Clock clock;

    public IdentityStandings(
            IdentityVerificationRepository verifications,
            IdentityVerifications identities,
            ComplianceOverrides overrides,
            LegalSubjects legalSubjects,
            VerificationProperties properties,
            Clock clock) {
        this.verifications = verifications;
        this.identities = identities;
        this.overrides = overrides;
        this.legalSubjects = legalSubjects;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public VerificationStanding of(UUID creatorId) {
        if (!properties.required()) {
            return VerificationStanding.NOT_REQUIRED;
        }
        if (overrides.isWaived(creatorId, ComplianceRequirement.IDENTITY_VERIFICATION)) {
            return VerificationStanding.WAIVED;
        }
        return verifications
                .findByUserId(creatorId)
                .map(row -> standingOf(row, clock.instant()))
                .orElse(VerificationStanding.NEVER_REQUESTED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The subject kind the request is opened against comes from #430's legal subject when the
     * creator has recorded one, and defaults to {@code INDIVIDUAL} when they have not. The
     * default matters more than it looks: {@code DocumentKind.isFor} refuses a company
     * registration document against an individual, so a company whose request was opened as an
     * individual would find the one document it has to send rejected as the wrong kind. A
     * creator who has recorded nothing has nothing to send either way, and correcting the kind
     * is what {@code IdentityVerification.submitted} does when the documents arrive.
     */
    @Override
    @Transactional
    public void requestIfNeeded(UUID creatorId) {
        VerificationStanding standing = of(creatorId);
        if (!standing.asksTheCreatorForSomething()) {
            return;
        }
        SubjectKind kind = legalSubjects
                .of(creatorId)
                .map(LegalSubject::subjectKind)
                .orElse(SubjectKind.INDIVIDUAL);
        IdentityVerification opened = identities.forCreator(creatorId, kind);
        log.info(
                "Creator {} stands at {}; identity verification {} is what the payout is waiting on",
                creatorId,
                standing,
                opened.getId());
    }

    private static VerificationStanding standingOf(IdentityVerification row, Instant now) {
        return switch (row.getState()) {
            case REQUESTED -> VerificationStanding.AWAITING_DOCUMENTS;
            case SUBMITTED -> VerificationStanding.UNDER_REVIEW;
            case REJECTED -> VerificationStanding.REJECTED;
            case EXPIRED -> VerificationStanding.EXPIRED;
            case APPROVED -> hasAgedOut(row, now) ? VerificationStanding.EXPIRED : VerificationStanding.VERIFIED;
        };
    }

    private static boolean hasAgedOut(IdentityVerification row, Instant now) {
        Instant expiry = row.getExpiresAt();
        return expiry != null && !now.isBefore(expiry);
    }

    /** What the console draws beside a held payout: the standing and why, in one read. */
    @Transactional(readOnly = true)
    public Optional<IdentityVerification> rowFor(UUID creatorId) {
        return verifications.findByUserId(creatorId);
    }
}
