package az.ideanest.legal.application;

import az.ideanest.audit.AuditEnvironment;
import az.ideanest.legal.domain.DocumentAcceptance;
import az.ideanest.legal.domain.DocumentKind;
import az.ideanest.legal.domain.LegalDocument;
import az.ideanest.legal.infrastructure.DocumentAcceptanceRepository;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.ReaderLocale;
import az.ideanest.shared.legal.AgreementInForce;
import az.ideanest.shared.legal.AgreementKind;
import az.ideanest.shared.legal.Agreements;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the gates ask, answered by the module that holds the rows — issues #425, #426, #427.
 *
 * <p><strong>The whole of this module's public surface to the rest of the platform.</strong>
 * The project module names {@code shared.legal.Agreements} and gets an
 * {@link AgreementInForce}; the pledge module does the same. Neither names a
 * {@code LegalDocument}, a {@code DocumentAcceptance}, or a table here.
 * {@code ModuleBoundaryTests} checks that.
 *
 * <p><strong>It fails open, and {@code Agreements} argues why at length.</strong> The short
 * version: a subscription gate that failed open would give the product away and the creator
 * meeting it can fix it by paying; a legal gate that failed closed would refuse every
 * campaign and every pledge on the platform with a message telling people to accept a
 * document that does not exist. So an agreement that has not been published is not a
 * requirement, and the day #439 publishes the text the gates bite for everybody without a
 * deployment.
 *
 * <p><strong>The address and the user agent are taken, not asked for.</strong>
 * {@code AuditEnvironment} makes that argument for the audit trail and it holds here more
 * strongly: an acceptance is evidence about a person, and an actor who could name their own
 * source address would be writing the alibi as well as the record. Everything is null when
 * there is no request — a test calling directly, a job — and a row that invented a client
 * would be worse than a row that says so.
 */
@Service
public class LegalAgreements implements Agreements {

    private static final Logger log = LoggerFactory.getLogger(LegalAgreements.class);

    private final LegalDocuments documents;
    private final DocumentAcceptanceRepository acceptances;
    private final Clock clock;

    public LegalAgreements(
            LegalDocuments documents, DocumentAcceptanceRepository acceptances, Clock clock) {
        this.documents = documents;
        this.acceptances = acceptances;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The governing row is the Azerbaijani one, and its absence is not an error here.
     * {@code LegalDocuments.publish} refuses to publish a version without it, so a published
     * version always has one; a kind with no published version at all answers empty, which
     * is the fail-open case.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<AgreementInForce> inForce(AgreementKind kind) {
        return documents.inForce(DocumentKind.of(kind)).stream()
                .filter(document -> document.getLocale().equals(ReaderLocale.PRIMARY))
                .findFirst()
                .map(governing -> new AgreementInForce(
                        kind, governing.getId(), governing.getVersion(), governing.getEffectiveFrom()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAccepted(UUID accountId, AgreementInForce agreement) {
        return accountId != null && acceptances.existsFor(accountId, agreement.documentId());
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>{@code MANDATORY}, following {@code Outbox}.</strong> An acceptance is a
     * precondition of the thing it accompanies, and the two have to commit together: a
     * pledge confirmed without its acknowledgement recorded, and an acknowledgement recorded
     * against a confirmation that then rolled back, are both rows that say something untrue
     * about what a person did. A propagation that would quietly start its own transaction is
     * how the second one happens, so this refuses to run outside the caller's.
     *
     * <p><strong>Idempotent, and it has to be checked twice.</strong> The read answers the
     * ordinary retry; the catch answers two requests arriving together, which the read
     * cannot, because both of them see nothing. V65's unique index is what makes one of them
     * lose.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void accept(UUID accountId, AgreementInForce agreement) {
        if (acceptances.existsFor(accountId, agreement.documentId())) {
            return;
        }

        AuditEnvironment environment = AuditEnvironment.current();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        DocumentAcceptance acceptance = DocumentAcceptance.of(
                Identifiers.newIdentifier(),
                accountId,
                agreement.documentId(),
                now,
                environment.sourceAddress(),
                environment.userAgent());

        try {
            acceptances.saveAndFlush(acceptance);
        } catch (DataIntegrityViolationException raced) {
            // The other request won. Its row says the same thing this one would have, so
            // there is nothing to do and nothing to report: an acceptance is not a thing
            // that can happen twice, and telling a client it already agreed would be
            // answering a retry with an error.
            log.debug(
                    "Account {} accepted {} version {} concurrently; keeping the first row",
                    accountId,
                    agreement.kind(),
                    agreement.version());
            return;
        }

        // No address and no user agent in the line: an acceptance is evidence about a
        // person, and a log is not the place it is kept.
        log.info("Account {} accepted {} version {}", accountId, agreement.kind(), agreement.version());
    }

    /**
     * A person accepting an agreement on their own account, outside any gate.
     *
     * <p><strong>Where a creator satisfies #426.</strong> The backer agreement is accepted as
     * part of confirming a pledge, because §22.3 wants it inside the flow; the creator
     * agreement has no such moment, so there is an endpoint. Without one the gate would be
     * unsatisfiable — a refusal telling somebody to accept a document with no way to accept
     * it is worse than no gate at all.
     *
     * <p><strong>The version is sent and checked, not inferred.</strong> Same reason as the
     * checkout's: recording "they accepted whatever was current when the request arrived"
     * would put an acknowledgement of version 4 against somebody who read version 3 on a page
     * they opened this morning.
     *
     * @throws AgreementNotPublishedException when nothing of this kind is in force. Distinct
     *     from the gates' silence on purpose: a gate that lets a submission through because
     *     no document exists is correct, and an <em>accept</em> of a document that does not
     *     exist is a client that has got ahead of itself
     * @throws AgreementVersionStaleException when the version offered is not the one in force
     */
    @Transactional
    public AgreementInForce acceptOwn(UUID accountId, AgreementKind kind, int version) {
        AgreementInForce agreement =
                inForce(kind).orElseThrow(() -> new AgreementNotPublishedException(kind));
        if (agreement.version() != version) {
            throw new AgreementVersionStaleException(kind, agreement.version(), version);
        }
        accept(accountId, agreement);
        return agreement;
    }

    /**
     * When this account accepted that version, if it has.
     *
     * <p>Read by {@code GET /v1/me/agreements}, so that a creator opening the editor is told
     * what they have already agreed to rather than being asked again.
     */
    @Transactional(readOnly = true)
    public Optional<Instant> acceptedAt(UUID accountId, AgreementInForce agreement) {
        return acceptances
                .find(accountId, agreement.documentId())
                .map(DocumentAcceptance::getAcceptedAt);
    }

    /**
     * The version in force of one of the two gated agreements, in a reader's language.
     *
     * <p>Here rather than on {@link LegalDocuments} because the callers that want it are the
     * ones that already speak {@link AgreementKind} — the checkout, which has to show the
     * backer agreement inside the pledge flow (§22.3), and the editor, which sends a creator
     * to the creator agreement.
     */
    @Transactional(readOnly = true)
    public Optional<LegalDocument> text(AgreementKind kind, String locale) {
        return documents.inForce(DocumentKind.of(kind), locale);
    }
}
