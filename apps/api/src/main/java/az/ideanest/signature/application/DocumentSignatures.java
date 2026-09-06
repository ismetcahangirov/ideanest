package az.ideanest.signature.application;

import az.ideanest.shared.Identifiers;
import az.ideanest.shared.signature.DocumentSigning;
import az.ideanest.shared.signature.SignatureOnFile;
import az.ideanest.shared.signature.SigningProgress;
import az.ideanest.shared.signature.SigningStarted;
import az.ideanest.signature.domain.SignatureOutcome;
import az.ideanest.signature.domain.SignatureProvider;
import az.ideanest.signature.domain.SignatureRecord;
import az.ideanest.signature.domain.SignatureRequest;
import az.ideanest.signature.domain.SignatureResult;
import az.ideanest.signature.domain.SignatureSession;
import az.ideanest.signature.domain.SignatureSessionRecord;
import az.ideanest.signature.domain.StoredSignature;
import az.ideanest.signature.infrastructure.SignatureRecordRepository;
import az.ideanest.signature.infrastructure.SignatureSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller #428 said it was waiting for — issue #429's half of it.
 *
 * <p>#428 built a provider abstraction and V67 built a table, and said plainly that nothing
 * consumed either: "#428 ships the mechanism; #429 is the caller." This is that caller, and it
 * is deliberately the only one. The legal module asks {@link DocumentSigning} for a signature
 * over a hash; it never learns that SİMA exists, which is the rule
 * {@code SignatureProviderBoundaryTests} checks.
 *
 * <h2>What this class owns and what it refuses to know</h2>
 *
 * <p>It owns sessions, signatures and the mapping between the provider's vocabulary and the
 * shared one. It knows nothing about creator agreements, thresholds or name matching: a
 * signature is over a hash, the hash is the caller's, and whether the name on the certificate
 * is the right name is a question about a legal subject that this module has never heard of.
 * #429 puts that check in the legal module for that reason.
 */
@Service
public class DocumentSignatures implements DocumentSigning {

    private static final Logger log = LoggerFactory.getLogger(DocumentSignatures.class);

    private final SignatureProviders providers;
    private final SignatureSessionRepository sessions;
    private final SignatureRecordRepository signatures;
    private final Clock clock;

    public DocumentSignatures(
            SignatureProviders providers,
            SignatureSessionRepository sessions,
            SignatureRecordRepository signatures,
            Clock clock) {
        this.providers = providers;
        this.sessions = sessions;
        this.signatures = signatures;
        this.clock = clock;
    }

    @Override
    public boolean isAvailable() {
        return providers.primary().isPresent();
    }

    @Override
    @Transactional
    public SigningStarted begin(
            UUID signerId, String documentHash, String purpose, String signerFin, String signerMobile) {
        SignatureProvider provider = primary();
        SignatureSession session =
                provider.begin(new SignatureRequest(documentHash, signerFin, signerMobile, purpose));
        sessions.save(new SignatureSessionRecord(
                Identifiers.newIdentifier(), signerId, provider.name(), session, documentHash, null, purpose));
        log.info("Signing session {} opened for {} through {}", session.sessionId(), signerId, provider.name());
        return new SigningStarted(session.sessionId(), session.verificationCode(), session.expiresAt());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Three cases, and the ordering matters. A session already resolved answers from the row
     * — the provider is asked once and only once, because a second resolve of a signed session
     * would write a second {@code signatures} row for one act of signing. A session past its
     * deadline is expired without asking, for V71's reason: a state that waits for a job is
     * wrong for as long as the job is broken. Only a live, unresolved session reaches SİMA.
     */
    @Override
    @Transactional
    public SigningProgress resolve(UUID signerId, String sessionId) {
        SignatureProvider provider = primary();
        SignatureSessionRecord row = sessions
                .findByProviderAndProviderSessionIdAndSignerUserId(provider.name(), sessionId, signerId)
                .orElseThrow(() -> new UnknownSigningSessionException(sessionId));

        if (!row.isPending()) {
            return progressOf(row);
        }
        Instant now = clock.instant();
        if (row.hasExpiredBy(now)) {
            row.resolved(SignatureOutcome.EXPIRED, null, now);
            log.info("Signing session {} expired before it was answered", sessionId);
            return SigningProgress.expired("The session expired before it was answered.");
        }

        SignatureResult result = provider.resolve(sessionId);
        return switch (result.outcome()) {
            case PENDING -> SigningProgress.pending();
            case CANCELLED -> {
                row.resolved(SignatureOutcome.CANCELLED, null, now);
                yield SigningProgress.cancelled(result.failureDetail());
            }
            case EXPIRED -> {
                row.resolved(SignatureOutcome.EXPIRED, null, now);
                yield SigningProgress.expired(result.failureDetail());
            }
            case SIGNED -> SigningProgress.signed(store(row, result.signature(), now));
        };
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SignatureOnFile> find(UUID signerId, UUID signatureId) {
        return signatures
                .findById(signatureId)
                .filter(record -> record.signerUserId().equals(signerId))
                .map(DocumentSignatures::onFile);
    }

    /**
     * Write the signature and close the session, in one transaction.
     *
     * <p>The two are not separable. A signature stored without its session closed would be
     * re-fetched and stored again on the next poll; a session closed without its signature
     * stored would be a row saying SIGNED with nothing behind it, and V71's constraint refuses
     * that shape outright.
     *
     * <p><strong>The hash is not checked here.</strong> {@code SignatureProvider.verify} is what
     * compares a signature against a document, and #429's caller is what decides which document
     * it should have covered — this class was handed a hash and has no standing to say the hash
     * was wrong. What it does do is store the hash the provider reported rather than the one it
     * was given, so that a disagreement between them is visible to the caller instead of being
     * flattened here.
     */
    private SignatureOnFile store(SignatureSessionRecord session, StoredSignature signed, Instant now) {
        SignatureRecord stored = signatures
                .findByProviderAndProviderSessionId(signed.provider(), signed.providerSessionId())
                .orElseGet(() -> signatures.save(
                        new SignatureRecord(Identifiers.newIdentifier(), session.signerUserId(), signed)));
        session.resolved(SignatureOutcome.SIGNED, stored.id(), now);
        log.info(
                "Signing session {} produced signature {} for {}",
                session.providerSessionId(),
                stored.id(),
                session.signerUserId());
        return onFile(stored);
    }

    private SigningProgress progressOf(SignatureSessionRecord row) {
        return switch (row.outcome()) {
            case PENDING -> SigningProgress.pending();
            case CANCELLED -> SigningProgress.cancelled("The session was cancelled.");
            case EXPIRED -> SigningProgress.expired("The session expired before it was answered.");
            case SIGNED -> signatures
                    .findById(row.signatureId())
                    .map(DocumentSignatures::onFile)
                    .map(SigningProgress::signed)
                    .orElseThrow(() -> new IllegalStateException(
                            "Session " + row.id() + " is SIGNED and its signature is missing"));
        };
    }

    private static SignatureOnFile onFile(SignatureRecord record) {
        StoredSignature signature = record.asStoredSignature();
        return new SignatureOnFile(
                record.id(),
                signature.provider().name(),
                signature.documentHash(),
                signature.subject().name(),
                signature.subject().fin(),
                signature.signedAt());
    }

    private SignatureProvider primary() {
        return providers.primary().orElseThrow(SigningUnavailableException::new);
    }
}
