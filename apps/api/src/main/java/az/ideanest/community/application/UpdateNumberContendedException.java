package az.ideanest.community.application;

import java.util.UUID;

/**
 * Two writers allocated the same update number and the database refused the second.
 *
 * <p><strong>Reachable on one campaign's first update and nowhere else.</strong>
 * {@code ProjectUpdateRepository#lockNewest} serialises every subsequent allocation
 * behind the newest row; a campaign with no updates has no row to lock, so two
 * requests arriving in the same instant can both compute 1 and
 * {@code project_updates_number_key} refuses one of them.
 *
 * <p>Answered as a 409 that says to try again rather than as a 500. Nothing was
 * written, the client's request was well formed, and the correct behaviour — send it
 * again — is one the client can take without a person being involved. A 500 would
 * describe a bug in us; this is a race the schema exists to decide.
 */
public class UpdateNumberContendedException extends RuntimeException {

    public UpdateNumberContendedException(UUID projectId, Throwable cause) {
        super("Another update on campaign " + projectId + " took the same number", cause);
    }
}
