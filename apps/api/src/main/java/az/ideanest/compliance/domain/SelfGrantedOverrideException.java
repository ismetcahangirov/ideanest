package az.ideanest.compliance.domain;

import java.util.UUID;

/**
 * Somebody tried to waive a compliance requirement for their own account — #436.
 *
 * <p>Raised by {@link ComplianceOverride}'s constructor so that the caller gets a sentence,
 * and refused again by V66's {@code compliance_overrides_grantor_is_not_the_subject} so that
 * the rule holds against something that never runs that constructor. The constraint is the
 * guarantee; this is the error message.
 *
 * <p>In {@code domain} rather than {@code application} because the invariant is the entity's:
 * an override that names one person twice is not a valid row, and there is no service call for
 * which it would be.
 */
public class SelfGrantedOverrideException extends RuntimeException {

    private final transient UUID subjectUserId;
    private final transient ComplianceRequirement requirement;

    public SelfGrantedOverrideException(UUID subjectUserId, ComplianceRequirement requirement) {
        super("An account may not override %s for itself (%s).".formatted(requirement, subjectUserId));
        this.subjectUserId = subjectUserId;
        this.requirement = requirement;
    }

    public UUID subjectUserId() {
        return subjectUserId;
    }

    public ComplianceRequirement requirement() {
        return requirement;
    }
}
