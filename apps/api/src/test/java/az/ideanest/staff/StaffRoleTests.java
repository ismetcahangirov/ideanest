package az.ideanest.staff;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.shared.access.StaffCapability;
import az.ideanest.staff.application.StaffMember;
import az.ideanest.staff.domain.StaffRole;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What each role confers, and what the separation is for — issue #295.
 *
 * <p>#295 states the problem the old configured list had in one line: it "cannot express
 * 'may refund' against 'may moderate'". These assertions are that sentence, checked — a role
 * model whose roles all confer everything is the list it replaced, spelled differently.
 *
 * <p>A plain unit test: none of this is about persistence. What is in the database is which
 * roles an account holds; what each role means is a policy in {@link StaffRole}, and that is
 * exactly the thing a reviewer should be able to see change in a diff.
 */
class StaffRoleTests {

    @Test
    @DisplayName("moderating and refunding are held by different roles")
    void moderationAndMoneyAreSeparate() {
        // The whole of #295, as one assertion. If this ever passes trivially -- because both
        // roles gained both capabilities -- the role model has quietly become the list.
        assertThat(StaffRole.MODERATOR.capabilities()).contains(StaffCapability.MODERATE_CONTENT);
        assertThat(StaffRole.MODERATOR.capabilities()).doesNotContain(StaffCapability.ISSUE_REFUND);

        assertThat(StaffRole.FINANCE.capabilities()).contains(StaffCapability.ISSUE_REFUND);
        assertThat(StaffRole.FINANCE.capabilities()).doesNotContain(StaffCapability.MODERATE_CONTENT);
    }

    @Test
    @DisplayName("finance cannot approve the payouts it calculates")
    void financeCannotApproveItsOwnPayouts() {
        // §4.11 requires dual approval above a threshold, and a role conferring both issuing
        // and approving would make the second signature a formality whenever the finance team
        // is one person. APPROVE_PAYOUT is ADMINISTRATOR's alone, so the second signature is
        // somebody else by construction rather than by policy.
        assertThat(StaffRole.FINANCE.capabilities()).doesNotContain(StaffCapability.APPROVE_PAYOUT);
        assertThat(StaffRole.ADMINISTRATOR.capabilities()).contains(StaffCapability.APPROVE_PAYOUT);
    }

    @Test
    @DisplayName("only an administrator may grant a role")
    void onlyAdministratorsGrantRoles() {
        // Anybody who can grant themselves a capability effectively holds every capability,
        // so this is the check that decides what the rest of the enum is worth.
        for (StaffRole role : StaffRole.values()) {
            assertThat(role.capabilities().contains(StaffCapability.ADMINISTER_STAFF))
                    .withFailMessage("%s must not be able to grant roles", role)
                    .isEqualTo(role == StaffRole.ADMINISTRATOR);
        }
    }

    @Test
    @DisplayName("an administrator holds everything")
    void administratorHoldsEverything() {
        assertThat(StaffRole.ADMINISTRATOR.capabilities())
                .containsExactlyInAnyOrder(StaffCapability.values());
    }

    @Test
    @DisplayName("the curator role touches no personal data")
    void curatorIsTheNarrowestRole() {
        // The reason it exists at all: arranging the home page should not require being
        // trusted with the report queue or with anybody's email address.
        assertThat(StaffRole.CURATOR.capabilities())
                .doesNotContain(
                        StaffCapability.ADMINISTER_ACCOUNTS,
                        StaffCapability.MODERATE_CONTENT,
                        StaffCapability.HANDLE_SUPPORT,
                        StaffCapability.VIEW_FINANCE);
    }

    @ParameterizedTest
    @DisplayName("every role can read the audit trail")
    @EnumSource(StaffRole.class)
    void everyRoleCanReadTheTrail(StaffRole role) {
        // Deliberately wide. A trail only the people it would incriminate can read is a
        // trail; a trail every member of staff can read is a control.
        assertThat(role.capabilities()).contains(StaffCapability.VIEW_AUDIT);
    }

    @Test
    @DisplayName("holding two roles holds the union of both")
    void rolesAreAdditive() {
        Set<StaffCapability> union = EnumSet.noneOf(StaffCapability.class);
        union.addAll(StaffRole.MODERATOR.capabilities());
        union.addAll(StaffRole.FINANCE.capabilities());

        StaffMember member = new StaffMember(
                UUID.randomUUID(), Set.of(StaffRole.MODERATOR, StaffRole.FINANCE), union, false);

        // Union rather than intersection or precedence, because no role here takes a
        // capability away -- so there is nothing for a precedence rule to resolve.
        assertThat(member.holds(StaffCapability.MODERATE_CONTENT)).isTrue();
        assertThat(member.holds(StaffCapability.ISSUE_REFUND)).isTrue();
        assertThat(member.holds(StaffCapability.APPROVE_PAYOUT)).isFalse();
        assertThat(member.isStaff()).isTrue();
    }

    @Test
    @DisplayName("an account with no roles is not staff and holds nothing")
    void nobodyIsNotStaff() {
        StaffMember nobody = StaffMember.none(UUID.randomUUID());

        assertThat(nobody.isStaff()).isFalse();
        for (StaffCapability capability : StaffCapability.values()) {
            assertThat(nobody.holds(capability)).isFalse();
        }
    }

    @Test
    @DisplayName("every capability is conferred by at least one role")
    void noCapabilityIsUnreachable() {
        // A capability no role grants is an endpoint nobody can ever call, which is a
        // deployment with a screen that always refuses and no way to find out why.
        Set<StaffCapability> reachable = EnumSet.noneOf(StaffCapability.class);
        for (StaffRole role : StaffRole.values()) {
            reachable.addAll(role.capabilities());
        }

        assertThat(reachable).containsExactlyInAnyOrder(StaffCapability.values());
    }
}
