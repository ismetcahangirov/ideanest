package az.ideanest.staff.application;

import az.ideanest.shared.access.StaffCapability;
import java.util.UUID;

/**
 * The caller works here and this is not theirs to do — #295.
 *
 * <p><strong>Distinct from {@link NotAModeratorException}, and the distinction is the
 * whole of this issue.</strong> Before the role model there was one refusal, because
 * there was one question: a caller was staff or was not. A moderator opening the refund
 * console got the same 403 as a stranger, which told the moderator that the console was
 * broken rather than that the screen was not theirs.
 *
 * <p>So this carries the capability that was wanted, and the console prints it. A member
 * of staff who is told "this needs ISSUE_REFUND, which your roles do not include" knows
 * to go and ask for it; one told "forbidden" goes looking for a bug.
 *
 * <p><strong>Naming the missing capability is deliberate and is not a disclosure.</strong>
 * The capability vocabulary is in the published contract and the console renders the
 * whole of it on the front door already. What is not said is who holds it — that is
 * {@code GET /v1/admin/staff}, which needs {@code ADMINISTER_STAFF} of its own.
 */
public class InsufficientStaffCapabilityException extends RuntimeException {

    private final transient StaffCapability required;

    public InsufficientStaffCapabilityException(UUID accountId, StaffCapability required) {
        super("Account " + accountId + " does not hold " + required);
        this.required = required;
    }

    /** What the caller would have needed, for the message the console shows them. */
    public StaffCapability required() {
        return required;
    }
}
