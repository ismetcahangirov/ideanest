package az.ideanest.legal.infrastructure;

import az.ideanest.legal.domain.DocumentAcceptance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V65's acceptances, by the three questions asked of them — issue #425.
 *
 * <p><strong>{@link #existsFor} is the gates' question</strong>, asked once per campaign
 * submission and once per pledge confirmation, and served by
 * {@code document_acceptances_by_user}. It exists rather than the caller loading the row
 * because the gate wants a boolean and loading an entity to discard it is how a hot path
 * acquires an allocation nobody can attribute.
 *
 * <p><strong>{@link #forAccount} is the console's</strong>: what has this person agreed to,
 * and when. It joins to the document because an acceptance on its own is two identifiers,
 * and a screen showing two identifiers is a screen nobody can read.
 */
public interface DocumentAcceptanceRepository extends JpaRepository<DocumentAcceptance, UUID> {

    /** Whether this account has accepted that exact version. */
    @Query(
            """
            SELECT COUNT(a) > 0 FROM DocumentAcceptance a
             WHERE a.userId = :userId AND a.documentId = :documentId
            """)
    boolean existsFor(@Param("userId") UUID userId, @Param("documentId") UUID documentId);

    /**
     * The acceptance itself, for the idempotent path.
     *
     * <p>{@code Agreements.accept} is idempotent against V65's unique index, and a caller
     * that raced another one reads the winner's row back through this rather than being
     * told it agreed twice.
     */
    @Query(
            """
            SELECT a FROM DocumentAcceptance a
             WHERE a.userId = :userId AND a.documentId = :documentId
            """)
    Optional<DocumentAcceptance> find(@Param("userId") UUID userId, @Param("documentId") UUID documentId);

    /** Everything this account has ever accepted, newest first. The console's read. */
    @Query("SELECT a FROM DocumentAcceptance a WHERE a.userId = :userId ORDER BY a.acceptedAt DESC")
    List<DocumentAcceptance> forAccount(@Param("userId") UUID userId);
}
