package az.ideanest.staff.application;

import java.util.UUID;

/**
 * A role was to be granted to an account that does not exist — #295.
 *
 * <p>404, and deliberately the same answer for a deleted account as for an identifier
 * that never named anything. {@code AdminUserController} draws the line in the same
 * place: an endpoint that distinguished the two would confirm that somebody used to have
 * an account here, to a caller holding nothing but a guessed identifier.
 */
public class UnknownStaffAccountException extends RuntimeException {

    public UnknownStaffAccountException(UUID accountId) {
        super("No account " + accountId);
    }
}
