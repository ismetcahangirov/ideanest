package az.ideanest.ticket.application;

import java.util.UUID;

/**
 * A ticket was to be raised for, or assigned to, an account that does not exist — #310.
 *
 * <p>404, and deliberately the same answer for a deleted account as for an identifier that
 * never named one — the line {@code AdminUserController} draws.
 *
 * <p>One exception for both cases. The requester and the assignee are different roles on a
 * ticket and the same mistake when the identifier is wrong, and two exception types would
 * mean two handlers saying the same sentence.
 */
public class UnknownRequesterException extends RuntimeException {

    public UnknownRequesterException(UUID accountId) {
        super("No account " + accountId);
    }
}
