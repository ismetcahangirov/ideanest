package az.ideanest.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.shared.observability.Correlation;
import az.ideanest.shared.observability.Redaction;
import az.ideanest.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What {@link AuditLog} guarantees, and what it deliberately does not.
 *
 * <p>The three that carry the design are {@link #aRecordCommitsWithTheChangeItDescribes()},
 * {@link #aRecordIsRolledBackWithTheChangeThatDidNotHappen()} and
 * {@link #anIndependentRecordSurvivesTheCallersRollback()}. Between them they are
 * the whole transactional argument: an action and its record are one commit, a
 * change that did not happen leaves no record claiming it did, and a refusal is
 * still recordable by a caller that is about to roll back.
 *
 * <p>Against a real PostgreSQL rather than a mock repository, because two of the
 * three are assertions about what a commit and a rollback actually do.
 */
class AuditLogTests extends AbstractIntegrationTest {

    @Autowired
    private AuditLog audit;

    @Autowired
    private AuditEntryRepository entries;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    private TransactionTemplate transactions() {
        if (transactions == null) {
            transactions = new TransactionTemplate(transactionManager);
        }
        return transactions;
    }

    @AfterEach
    void clearCorrelation() {
        // A container thread is reused, and an identifier left behind would be
        // written onto the next test's rows — which looks correct, and is the exact
        // failure CorrelationFilter clears the MDC in a finally to avoid.
        MDC.clear();
    }

    /**
     * There is no cleanup, and there cannot be: V21 refuses DELETE. Every test
     * therefore invents the thing it acts on and asserts only about rows carrying
     * that identifier — which is how anybody reading this table has to work anyway.
     */
    private List<AuditEntry> rowsAbout(UUID entityId) {
        return entries.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                AuditAction.PROJECT_APPROVED.entityType(), entityId);
    }

    // -----------------------------------------------------------------------
    // The transaction
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a record commits with the change it describes")
    void aRecordCommitsWithTheChangeItDescribes() {
        UUID projectId = Identifiers.newIdentifier();
        UUID moderatorId = Identifiers.newIdentifier();

        UUID recorded = transactions().execute(status -> audit.record(
                AuditAction.PROJECT_APPROVED,
                projectId,
                AuditActor.moderator(moderatorId),
                AuditOutcome.SUCCEEDED,
                "SUBMITTED -> APPROVED"));

        assertThat(entries.findById(recorded)).isPresent();
        assertThat(rowsAbout(projectId)).singleElement().satisfies(entry -> {
            assertThat(entry.getActorId()).isEqualTo(moderatorId);
            assertThat(entry.getActorType()).isEqualTo(AuditActorType.MODERATOR);
            assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(entry.getAction()).isEqualTo("project.approved");
            assertThat(entry.getDetail()).isEqualTo("SUBMITTED -> APPROVED");
            // The database's, and therefore not a value any caller chose.
            assertThat(entry.getOccurredAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("a record is rolled back with the change that did not happen")
    void aRecordIsRolledBackWithTheChangeThatDidNotHappen() {
        UUID projectId = Identifiers.newIdentifier();

        // The half people forget. A record that survived its own transaction's
        // rollback would assert that a campaign was approved when it was not, and a
        // trail with false entries is worse than one with gaps: a gap is visible,
        // and a false entry is evidence.
        assertThatThrownBy(() -> transactions().execute(status -> {
                    audit.record(
                            AuditAction.PROJECT_APPROVED,
                            projectId,
                            AuditActor.moderator(Identifiers.newIdentifier()),
                            AuditOutcome.SUCCEEDED);
                    throw new IllegalStateException("the approval failed after the record");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rowsAbout(projectId)).isEmpty();
    }

    @Test
    @DisplayName("recording outside a transaction is refused rather than quietly committed")
    void recordingNeedsSomethingToBeAtomicWith() {
        // MANDATORY. REQUIRED would open a transaction here and commit the row on
        // its own, which is the divergence this class exists to prevent — arrived at
        // by the convenience of not having to say so. The failure belongs at the
        // call site, in a test, and not in production as a guarantee that silently
        // is not one.
        assertThatThrownBy(() -> audit.record(
                        AuditAction.PROJECT_APPROVED,
                        Identifiers.newIdentifier(),
                        AuditActor.system(),
                        AuditOutcome.SUCCEEDED))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("an independent record survives the caller's rollback")
    void anIndependentRecordSurvivesTheCallersRollback() {
        UUID projectId = Identifiers.newIdentifier();
        UUID moderatorId = Identifiers.newIdentifier();

        // The refusal case. A caller recording REFUSED is about to throw, so a row
        // written in its transaction would be undone by the very refusal it
        // describes — and "somebody tried and was not allowed to" would be
        // unrecordable by construction.
        assertThatThrownBy(() -> transactions().execute(status -> {
                    audit.recordIndependently(
                            AuditAction.PROJECT_APPROVED,
                            projectId,
                            AuditActor.moderator(moderatorId),
                            AuditOutcome.REFUSED,
                            "not a moderator");
                    throw new IllegalStateException("and then the caller refused the request");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rowsAbout(projectId)).singleElement().satisfies(entry -> {
            assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(entry.getDetail()).isEqualTo("not a moderator");
        });
    }

    // -----------------------------------------------------------------------
    // What the row carries
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a recorded action carries the correlation identifiers of the request it came from")
    void theCorrelationIdentifiersAreOnTheRow() {
        UUID projectId = Identifiers.newIdentifier();
        String requestId = "req-" + Identifiers.newIdentifier();
        String traceId = Correlation.newTraceId();

        MDC.put(Correlation.REQUEST_ID, requestId);
        MDC.put(Correlation.TRACE_ID, traceId);

        transactions().execute(status -> audit.record(
                AuditAction.PROJECT_APPROVED,
                projectId,
                AuditActor.moderator(Identifiers.newIdentifier()),
                AuditOutcome.SUCCEEDED));

        // Without these the row cannot be joined to the log lines of the request
        // that produced it, and correlating by timestamp is guesswork on a service
        // handling more than one request per millisecond. Taken from the MDC rather
        // than from an argument, so no call site can leave them out.
        assertThat(rowsAbout(projectId)).singleElement().satisfies(entry -> {
            assertThat(entry.getRequestId()).isEqualTo(requestId);
            assertThat(entry.getTraceId()).isEqualTo(traceId);
        });
    }

    @Test
    @DisplayName("an identifier the log would have refused is not written here either")
    void aMalformedCorrelationIdentifierIsDropped() {
        UUID projectId = Identifiers.newIdentifier();

        // Correlation.acceptableIdentifier is what stands between an inbound header
        // and the log stream. The same rule applies here, because the whole value of
        // the column is that the row and the lines carry the same string: a value
        // the log rejected and this accepted would join to nothing.
        MDC.put(Correlation.REQUEST_ID, "short");
        MDC.put(Correlation.TRACE_ID, "a trace id with spaces in it");

        transactions().execute(status -> audit.record(
                AuditAction.PROJECT_APPROVED,
                projectId,
                AuditActor.moderator(Identifiers.newIdentifier()),
                AuditOutcome.SUCCEEDED));

        assertThat(rowsAbout(projectId)).singleElement().satisfies(entry -> {
            assertThat(entry.getRequestId()).isNull();
            assertThat(entry.getTraceId()).isNull();
        });
    }

    @Test
    @DisplayName("§17.4's redaction applies to whatever a call site says")
    void detailIsRedacted() {
        UUID projectId = Identifiers.newIdentifier();

        transactions().execute(status -> audit.record(
                AuditAction.PROJECT_APPROVED,
                projectId,
                AuditActor.moderator(Identifiers.newIdentifier()),
                AuditOutcome.REFUSED,
                "rejected after a complaint from backer@example.com"));

        // An audit trail is not a licence to retain personal data, and this is the
        // worst possible table to acquire some by accident: nothing may edit a row
        // to take it out again.
        assertThat(rowsAbout(projectId)).singleElement().satisfies(entry -> {
            assertThat(entry.getDetail()).doesNotContain("backer@example.com").contains(Redaction.MASK);
        });
    }

    @Test
    @DisplayName("a detail longer than the bound is cut, after it has been redacted")
    void detailIsBounded() {
        UUID projectId = Identifiers.newIdentifier();
        // Longer than ideanest.audit.detail-max-length, which is a thousand.
        String tooLong = "a".repeat(4000);

        transactions().execute(status -> audit.record(
                AuditAction.PROJECT_APPROVED,
                projectId,
                AuditActor.moderator(Identifiers.newIdentifier()),
                AuditOutcome.SUCCEEDED,
                tooLong));

        // Nothing prunes this table row by row, so text that arrives stays until the
        // month holding it is detached. An unbounded detail is unbounded growth in
        // the one table that cannot be tidied afterwards.
        assertThat(rowsAbout(projectId)).singleElement().satisfies(entry -> assertThat(entry.getDetail())
                .hasSize(1000));
    }

    // -----------------------------------------------------------------------
    // The actor
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("impersonation is modelled, is null on every row this release writes, and is refused when incoherent")
    void impersonationIsModelledAndUnused() {
        UUID projectId = Identifiers.newIdentifier();
        UUID staff = Identifiers.newIdentifier();
        UUID subject = Identifiers.newIdentifier();

        transactions().execute(status -> audit.record(
                AuditAction.PROJECT_APPROVED, projectId, AuditActor.moderator(staff), AuditOutcome.SUCCEEDED));
        assertThat(rowsAbout(projectId))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getOnBehalfOfId()).isNull());

        // The field is built before #104 builds the feature, because a row written
        // by an impersonating actor with nowhere to put the subject reads as the
        // subject having acted — which is worse than no record.
        UUID impersonated = Identifiers.newIdentifier();
        transactions().execute(status -> audit.record(
                AuditAction.PROJECT_APPROVED,
                impersonated,
                AuditActor.moderator(staff).onBehalfOf(subject),
                AuditOutcome.SUCCEEDED));
        assertThat(rowsAbout(impersonated))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getOnBehalfOfId()).isEqualTo(subject));

        // And the two rules V21 also holds, caught here with a stack trace naming
        // the call site rather than at the statement.
        assertThatThrownBy(() -> AuditActor.moderator(staff).onBehalfOf(staff))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditActor.system().onBehalfOf(subject))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a system actor carries no account and an account actor must")
    void theActorIsCoherent() {
        assertThatThrownBy(() -> new AuditActor(AuditActorType.SYSTEM, Identifiers.newIdentifier(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditActor(AuditActorType.USER, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(AuditActor.system().id()).isNull();
        assertThat(AuditActor.user(Identifiers.newIdentifier()).type()).isEqualTo(AuditActorType.USER);
    }
}
