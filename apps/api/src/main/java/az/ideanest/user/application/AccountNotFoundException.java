package az.ideanest.user.application;

import java.util.UUID;

/**
 * No such account — §4.11's AD-04.
 *
 * <p>Raised for an identifier that names nothing and for one that names a deleted
 * account, deliberately the same answer. An anonymised account has nothing left to
 * inspect and nothing left to stop; distinguishing the two would tell whoever is asking
 * that somebody was here and has gone, which is the one fact §17.4 exists to remove.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID userId) {
        super("No account " + userId);
    }
}
