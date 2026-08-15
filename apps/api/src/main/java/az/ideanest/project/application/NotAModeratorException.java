package az.ideanest.project.application;

import java.util.UUID;

/**
 * The caller is signed in and is not platform staff.
 *
 * <p><strong>403, not the 404 {@link ProjectNotFoundException} gives.</strong>
 * The two hide different things. A draft campaign is confidential, so its
 * existence is not confirmed to somebody who has no business with it. The
 * moderation endpoints are published in {@code docs/architecture.md} §10.2 and
 * hide nothing, and the refusal happens before any campaign is loaded — so there
 * is no campaign to be evasive about, and a 404 here would tell an operator whose
 * configuration is wrong that the endpoint does not exist.
 */
public class NotAModeratorException extends RuntimeException {

    public NotAModeratorException(UUID accountId) {
        super("Account " + accountId + " is not a platform moderator");
    }
}
