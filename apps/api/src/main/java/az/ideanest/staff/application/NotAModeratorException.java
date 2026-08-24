package az.ideanest.staff.application;

import java.util.UUID;

/**
 * The caller is signed in and does not work here.
 *
 * <p><strong>403, not a 404.</strong> A draft campaign is confidential, so its existence
 * is not confirmed to somebody who has no business with it; the administration endpoints
 * are published in {@code docs/architecture.md} §10.2 and hide nothing, and the refusal
 * happens before anything is loaded — so there is no record to be evasive about, and a
 * 404 here would tell an operator whose configuration is wrong that the endpoint does not
 * exist.
 *
 * <p><strong>It kept its name when it moved modules in #295.</strong> The class was
 * {@code project.application.NotAModeratorException} while the project module owned the
 * configured list of addresses that decided who was staff. Staff identity is not a fact
 * about campaigns — a finance-only member of staff has nothing to do with them — so the
 * role model and the refusal that comes out of it live here. Six exception handlers name
 * this type; renaming it at the same time as moving it would have made that diff about
 * two things at once, and {@code NOT_A_MODERATOR} is the code already published to every
 * console screen.
 *
 * @see InsufficientStaffCapabilityException for the caller who does work here and may not
 *     do this
 */
public class NotAModeratorException extends RuntimeException {

    public NotAModeratorException(UUID accountId) {
        super("Account " + accountId + " is not a platform moderator");
    }
}
