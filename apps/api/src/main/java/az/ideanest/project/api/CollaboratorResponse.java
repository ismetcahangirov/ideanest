package az.ideanest.project.api;

import az.ideanest.project.domain.Capability;
import az.ideanest.project.domain.Collaborator;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One person on a campaign, as the People tab sees them.
 *
 * <p>The response of every collaborator endpoint, for the reason {@link ProjectEdit}
 * gives: one shape means a client applies the same update after an invitation, a
 * change of capabilities, and an acceptance.
 *
 * <p><strong>Nulls are written out.</strong> The service serialises with
 * {@code non_null}, and here that would hide the difference between an invitation
 * nobody has accepted — {@code accountId} and {@code acceptedAt} genuinely absent —
 * and a key the client forgot to send.
 *
 * <p>There is no token in this response, and there must never be one. The
 * invitation token goes to the address it was issued to and nowhere else; a creator
 * who could read it out of their own response could accept on the invitee's behalf,
 * which is the one thing sending it by email is for.
 *
 * @param email the address the invitation was issued to. Still the identity of a
 *     pending row, and kept afterwards because it is what the creator recognises
 * @param accountId the account behind the address, once it has accepted. Null while
 *     the invitation is pending, including when the address has no account here
 * @param status derived rather than stored — see {@link #statusOf} for why the four
 *     values are what the client is given
 * @param capabilities exactly the names of {@link Capability}, sorted, so that two
 *     collaborators with the same grant are rendered identically
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CollaboratorResponse(
        UUID id,
        UUID projectId,
        UUID accountId,
        String email,
        List<String> capabilities,
        String status,
        UUID invitedById,
        Instant invitedAt,
        Instant expiresAt,
        Instant acceptedAt,
        Instant revokedAt) {

    public static CollaboratorResponse of(Collaborator collaborator, Instant now) {
        return new CollaboratorResponse(
                collaborator.getId(),
                collaborator.getProjectId(),
                collaborator.getAccountId(),
                collaborator.getInvitedEmail().value(),
                collaborator.getCapabilities().stream()
                        .map(Capability::name)
                        .sorted()
                        .toList(),
                statusOf(collaborator, now),
                collaborator.getInvitedBy(),
                collaborator.getCreatedAt(),
                collaborator.getExpiresAt(),
                collaborator.getAcceptedAt(),
                collaborator.getRevokedAt());
    }

    /**
     * What the row means right now.
     *
     * <p>Four values rather than the three timestamps on their own, because the tab
     * has to render one badge and the arithmetic behind it is a rule — "expired"
     * is a comparison against the current time, and a client that made that
     * comparison itself would disagree with the server about the minute an
     * invitation stopped working. Revocation wins over expiry: a withdrawn
     * invitation is withdrawn whether or not it had already lapsed.
     */
    private static String statusOf(Collaborator collaborator, Instant now) {
        if (collaborator.isRevoked()) {
            return "REVOKED";
        }
        if (collaborator.isActive()) {
            return "ACTIVE";
        }
        return collaborator.isExpired(now) ? "EXPIRED" : "PENDING";
    }
}
