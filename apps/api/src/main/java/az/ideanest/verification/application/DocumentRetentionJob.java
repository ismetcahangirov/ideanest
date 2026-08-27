package az.ideanest.verification.application;

import az.ideanest.shared.jobs.ScheduledJob;
import az.ideanest.verification.VerificationProperties;
import az.ideanest.verification.domain.IdentityDocument;
import az.ideanest.verification.domain.IdentityVerification;
import az.ideanest.verification.domain.VerificationState;
import az.ideanest.verification.infrastructure.IdentityDocumentRepository;
import az.ideanest.verification.infrastructure.IdentityVerificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The retention limit in #105's title, as a sweep — §17.4.
 *
 * <h2>Why this exists when a decision already erases</h2>
 *
 * <p>{@code IdentityVerifications} destroys the documents behind a verification in the same
 * transaction as the decision, which is the ordinary path and covers every submission
 * anybody looks at. This is the backstop, and it covers the two cases that path cannot
 * reach by construction:
 *
 * <ul>
 *   <li><strong>The submission nobody ever decides.</strong> A queue that stops being
 *       worked becomes an archive of passports, silently, at whatever rate creators
 *       register. {@code unreviewedRetention} bounds it.
 *   <li><strong>A document whose decision erased nothing</strong> — a row inserted by hand,
 *       a restore that reinstated one, a bug in a future path that forgets to call
 *       {@code erase}. The age check is on the document rather than on the verification,
 *       so it does not depend on the state machine being right.
 * </ul>
 *
 * <p>It also expires approvals that have aged past their life, because that is the same
 * pass over the same table and a second job for one {@code UPDATE} would be a second lease
 * and a second thing to notice has stopped running.
 *
 * <p>Throwing is how a failed pass is recorded: {@code JobRunner} counts the attempt,
 * releases the lease and backs off. Catching a failure to keep the log tidy would have the
 * sweep recorded as having run — and a retention sweep that is recorded as having run and
 * has not is the worst possible state for this particular table.
 */
@Component
public class DocumentRetentionJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentRetentionJob.class);

    /**
     * Daily, a little after three in the morning.
     *
     * <p>Not on the hour, for the reason every other job in this service is not: a platform
     * whose jobs all fire at {@code :00} is a platform whose database is briefly busy at
     * {@code :00}.
     */
    private static final String SCHEDULE = "0 40 3 * * *";

    private final IdentityVerificationRepository verifications;
    private final IdentityDocumentRepository documents;
    private final VerificationProperties properties;
    private final Clock clock;

    public DocumentRetentionJob(
            IdentityVerificationRepository verifications,
            IdentityDocumentRepository documents,
            VerificationProperties properties,
            Clock clock) {
        this.verifications = verifications;
        this.documents = documents;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "identity-document-retention";
    }

    @Override
    public String schedule() {
        return SCHEDULE;
    }

    @Override
    public void run() {
        sweep(clock.instant());
    }

    /**
     * One pass.
     *
     * @param now the instant this pass judges everything against, so that every row in it
     *     is judged against one moment rather than against a clock that moved while the
     *     pass was running
     * @return how many documents were destroyed
     */
    @Transactional
    public int sweep(Instant now) {
        expireApprovals(now);

        /*
         * The age is measured on the document, not on the verification, so this does not
         * depend on the state machine having been right. A decided submission is past the
         * short limit; an undecided one is past the long one; a document older than both is
         * caught whatever its verification says.
         */
        Instant decidedBefore = now.minus(properties.documents().retention());
        Instant undecidedBefore = now.minus(properties.documents().unreviewedRetention());

        Set<UUID> erase = new LinkedHashSet<>();
        for (IdentityDocument document : documents.findByUploadedAtBefore(undecidedBefore)) {
            erase.add(document.getVerificationId());
        }
        for (IdentityDocument document : documents.findByUploadedAtBefore(decidedBefore)) {
            verifications
                    .findById(document.getVerificationId())
                    .filter(verification -> verification.getState() != VerificationState.SUBMITTED)
                    .ifPresent(verification -> erase.add(verification.getId()));
        }

        if (erase.isEmpty()) {
            return 0;
        }

        int destroyed = documents.deleteForVerifications(List.copyOf(erase));
        for (UUID verificationId : erase) {
            verifications.findById(verificationId).ifPresent(verification -> verification.documentsErased(now));
        }

        // The count and never a creator. A line naming who had a passport destroyed would
        // put the fact this sweep exists to remove into the log stream instead (§17.4).
        log.info("Retention sweep destroyed {} identity document(s) across {} verifications.", destroyed, erase.size());
        return destroyed;
    }

    /**
     * Approvals past their life.
     *
     * <p>Moved rather than computed on read, so the state in the database is the state the
     * platform will act on. A verification that read as approved to one query and expired
     * to another would be the worst of both.
     */
    private void expireApprovals(Instant now) {
        List<IdentityVerification> aged =
                verifications.findByStateAndExpiresAtBefore(VerificationState.APPROVED, now);
        for (IdentityVerification verification : aged) {
            verification.expired(now);
        }
        if (!aged.isEmpty()) {
            log.info("Expired {} identity verification(s) past their approval life.", aged.size());
        }
    }
}
