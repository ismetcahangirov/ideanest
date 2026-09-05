package az.ideanest.legal.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.legal.domain.DocumentAcceptance;
import az.ideanest.legal.domain.LegalDocument;
import az.ideanest.legal.infrastructure.DocumentAcceptanceRepository;
import az.ideanest.legal.infrastructure.LegalDocumentRepository;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What one account has agreed to, and when — issue #425.
 *
 * <h2>Reading it is itself a privileged action</h2>
 *
 * <p>So it is audited, for {@code AUDIT_TRAIL_READ}'s reason: this is a read of somebody's
 * own record rather than of the platform's, it names an address they connected from, and a
 * screen whose use is not answerable is one that gets used for things it was not built for.
 * Recording it is cheap and its absence is only noticed during the investigation that
 * needed it.
 *
 * <p>{@link StaffCapability#ADMINISTER_ACCOUNTS} rather than {@code CONFIGURE_PLATFORM}:
 * publishing a document changes the platform, and reading what one person agreed to is a
 * question about an account, which is what the account console is. #436 gives this its own
 * row in §3.1's matrix; narrowing it is a change to the one line below.
 *
 * <h2>It joins, because two identifiers are not a record</h2>
 *
 * <p>An acceptance row is an account, a document identifier and a timestamp. A screen
 * showing that is a screen nobody can read, so the versions are loaded alongside and the
 * result says <em>which document, which version, in which language</em>. One extra query
 * for the whole list rather than one per row.
 */
@Service
public class AcceptanceRecords {

    private final DocumentAcceptanceRepository acceptances;
    private final LegalDocumentRepository documents;
    private final PlatformStaff staff;
    private final AuditLog audit;

    public AcceptanceRecords(
            DocumentAcceptanceRepository acceptances,
            LegalDocumentRepository documents,
            PlatformStaff staff,
            AuditLog audit) {
        this.acceptances = acceptances;
        this.documents = documents;
        this.staff = staff;
        this.audit = audit;
    }

    /**
     * Everything this account has accepted, newest first.
     *
     * <p>Not paged. An account accepts two documents and then a new version of one of them
     * every year or so; a cursor here would be machinery protecting nothing, and the whole
     * point of the screen is to be read at once.
     */
    @Transactional(readOnly = true)
    public List<AcceptedDocument> forAccount(UUID staffId, UUID accountId) {
        staff.requireCapability(staffId, StaffCapability.ADMINISTER_ACCOUNTS);

        List<DocumentAcceptance> rows = acceptances.forAccount(accountId);

        // The entity is the account whose record was read, not the member of staff, so that
        // "who has looked at this person's file" is one query. Recorded even when the list
        // is empty: that somebody looked is the fact, and finding nothing is a result.
        audit.record(
                AuditAction.ACCEPTANCE_RECORD_READ,
                accountId,
                AuditActor.user(staffId),
                AuditOutcome.SUCCEEDED,
                rows.size() + " acceptances");

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, LegalDocument> versions = documents
                .findAllById(rows.stream().map(DocumentAcceptance::getDocumentId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(LegalDocument::getId, Function.identity()));

        return rows.stream()
                .map(row -> new AcceptedDocument(row, versions.get(row.getDocumentId())))
                .sorted(Comparator.comparing((AcceptedDocument accepted) -> accepted.acceptance().getAcceptedAt())
                        .reversed())
                .toList();
    }

    /**
     * One acceptance and the version it names.
     *
     * <p>A record rather than a flattened row, because the API layer wants both halves and
     * assembling a third shape here would be this module deciding what a response looks
     * like.
     *
     * @param document never null in practice — V65's {@code ON DELETE RESTRICT} means an
     *     acceptance cannot outlive its version — and read defensively by the caller anyway,
     *     because a null here would be a schema failure and not a reason to fail a console
     *     screen
     */
    public record AcceptedDocument(DocumentAcceptance acceptance, LegalDocument document) {}
}
