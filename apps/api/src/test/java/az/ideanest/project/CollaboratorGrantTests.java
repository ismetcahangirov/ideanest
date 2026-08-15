package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.project.domain.Capability;
import az.ideanest.project.domain.Collaborator;
import az.ideanest.project.domain.Grants;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.Identifiers;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules of delegation, without a database or an HTTP request in sight.
 *
 * <p>A pure test for the same reason {@link ProjectStateMachineTests} is one: these
 * rules decide who may change a campaign that takes money from the public, and the
 * edges of a rule that can only be exercised through a container are edges nobody
 * exercises. {@code CollaboratorApiTests} then proves the same rules hold over HTTP,
 * which is a different claim — that they are actually wired up.
 */
class CollaboratorGrantTests {

    private static final Instant NOW = Instant.parse("2026-08-15T10:00:00Z");

    private static final Duration WEEK = Duration.ofDays(7);

    private static Collaborator invitation(Set<Capability> capabilities) {
        return Collaborator.invite(
                Identifiers.newIdentifier(),
                EmailAddress.of("collaborator@example.com"),
                new byte[Collaborator.HASH_LENGTH],
                Identifiers.newIdentifier(),
                capabilities,
                NOW,
                NOW.plus(WEEK));
    }

    private static Collaborator accepted(Set<Capability> capabilities) {
        Collaborator collaborator = invitation(capabilities);
        collaborator.accept(Identifiers.newIdentifier(), NOW);
        return collaborator;
    }

    // ------------------------------------------------------------------
    // The creator is not a row
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the creator holds everything without being a collaborator")
    void theCreatorNeedsNoRow() {
        Grants creator = Grants.ofCreator();

        // Their authority comes from projects.creator_id. A collaborators row for
        // them could be revoked or narrowed, which would be a way to lock somebody
        // out of their own campaign.
        assertThat(creator.isCreator()).isTrue();
        assertThat(creator.isEmpty()).isFalse();
        for (Capability capability : Capability.values()) {
            assertThat(creator.holds(capability)).isTrue();
        }
    }

    @Test
    @DisplayName("a collaborator with every capability is still not a creator")
    void everyCapabilityIsNotOwnership() {
        Grants everything = Grants.of(EnumSet.allOf(Capability.class));

        // The distinction that matters: what they may pass on. A holder of all eight
        // is refused MANAGE_COLLABORATORS as a grant, and the creator is not.
        assertThat(everything.isCreator()).isFalse();
        assertThat(everything.holds(Capability.MANAGE_COLLABORATORS)).isTrue();
        assertThat(everything.ungrantable(EnumSet.of(Capability.MANAGE_COLLABORATORS)))
                .containsExactly(Capability.MANAGE_COLLABORATORS);
    }

    // ------------------------------------------------------------------
    // Nobody grants more than they hold
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a collaborator cannot grant a capability they do not hold")
    void noEscalation() {
        Grants storyEditor = Grants.of(EnumSet.of(Capability.EDIT_STORY, Capability.MANAGE_COLLABORATORS));

        // Within their grant: allowed.
        assertThat(storyEditor.ungrantable(EnumSet.of(Capability.EDIT_STORY))).isEmpty();

        // Beyond it: refused, and the refusal names which ones so that the editor can
        // show the message against the checkbox rather than as "forbidden".
        assertThat(storyEditor.ungrantable(EnumSet.of(Capability.EDIT_STORY, Capability.VIEW_FINANCES)))
                .containsExactly(Capability.VIEW_FINANCES);

        // Otherwise the creator's decision to grant one capability is advisory: this
        // person invites an accomplice with all eight and the grant meant nothing.
        assertThat(storyEditor.ungrantable(EnumSet.allOf(Capability.class)))
                .hasSize(Capability.values().length - 1)
                .doesNotContain(Capability.EDIT_STORY);
    }

    @Test
    @DisplayName("only the creator may grant MANAGE_COLLABORATORS")
    void managingIsNotDelegable() {
        Grants manager = Grants.of(EnumSet.of(Capability.MANAGE_COLLABORATORS, Capability.EDIT_BASICS));

        // Holding it is enough to invite and to revoke.
        assertThat(manager.holds(Capability.MANAGE_COLLABORATORS)).isTrue();

        // Passing it on is not. A manager who could would grow the team indefinitely,
        // and every manager they added could do the same — so the bound is none
        // rather than one level.
        assertThat(manager.ungrantable(EnumSet.of(Capability.MANAGE_COLLABORATORS)))
                .containsExactly(Capability.MANAGE_COLLABORATORS);
        assertThat(manager.ungrantable(EnumSet.of(Capability.EDIT_BASICS))).isEmpty();

        assertThat(Grants.ofCreator().ungrantable(EnumSet.of(Capability.MANAGE_COLLABORATORS)))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // A grant is inert unless it is active
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a pending invitation grants nothing")
    void aPendingInvitationIsInert() {
        Collaborator pending = invitation(EnumSet.of(Capability.EDIT_BASICS));

        assertThat(pending.isActive()).isFalse();
        assertThat(Grants.of(pending).isEmpty()).isTrue();
        // Which is what makes an invitation to an address with no account safe to
        // issue: until somebody accepts it, it authorises nobody.
        assertThat(Grants.of(pending).holds(Capability.EDIT_BASICS)).isFalse();
    }

    @Test
    @DisplayName("a revoked grant is inert, and stays as a record")
    void aRevokedGrantIsInert() {
        Collaborator collaborator = accepted(EnumSet.of(Capability.EDIT_BASICS, Capability.VIEW_FINANCES));
        assertThat(Grants.of(collaborator).holds(Capability.VIEW_FINANCES)).isTrue();

        collaborator.revoke(Identifiers.newIdentifier(), NOW.plus(Duration.ofDays(1)));

        // One write, and every capability stops working at once. The row is still
        // there — it is the record that somebody had access between two dates, which
        // is exactly the question asked after a leak.
        assertThat(collaborator.isActive()).isFalse();
        assertThat(collaborator.isRevoked()).isTrue();
        assertThat(collaborator.getCapabilities()).contains(Capability.VIEW_FINANCES);
        assertThat(Grants.of(collaborator).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an active grant confers exactly what it says and nothing more")
    void anActiveGrantIsExact() {
        Grants grants = Grants.of(accepted(EnumSet.of(Capability.EDIT_STORY)));

        // A collaborator with EDIT_STORY is not a collaborator with EDIT_REWARDS,
        // which is the whole reason capabilities are granular.
        assertThat(grants.holds(Capability.EDIT_STORY)).isTrue();
        assertThat(grants.holds(Capability.EDIT_REWARDS)).isFalse();
        assertThat(grants.holds(Capability.VIEW_FINANCES)).isFalse();
        assertThat(grants.capabilities()).containsExactly(Capability.EDIT_STORY);
    }

    // ------------------------------------------------------------------
    // The invitation itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("acceptance is single use and refuses a second time")
    void acceptanceIsSingleUse() {
        Collaborator invitation = invitation(EnumSet.of(Capability.EDIT_BASICS));
        UUID account = Identifiers.newIdentifier();

        assertThat(invitation.isAcceptable(NOW)).isTrue();
        invitation.accept(account, NOW);

        // "Single use" that tolerates a second use is not single use. The row is
        // spent by a timestamp rather than by a delete, so a replay is
        // distinguishable from a token that never existed.
        assertThat(invitation.isAcceptable(NOW)).isFalse();
        assertThatThrownBy(() -> invitation.accept(account, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(invitation.getAcceptedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("an expired or revoked invitation cannot be accepted")
    void expiryAndRevocationCloseTheDoor() {
        Collaborator expired = invitation(EnumSet.of(Capability.EDIT_BASICS));
        assertThat(expired.isAcceptable(NOW.plus(WEEK))).isFalse();
        assertThat(expired.isExpired(NOW.plus(WEEK))).isTrue();
        // The boundary is exclusive: a link is dead at its expiry, not a moment
        // after it.
        assertThat(expired.isAcceptable(NOW.plus(WEEK).minusMillis(1))).isTrue();

        Collaborator withdrawn = invitation(EnumSet.of(Capability.EDIT_BASICS));
        withdrawn.revoke(Identifiers.newIdentifier(), NOW);
        assertThat(withdrawn.isAcceptable(NOW)).isFalse();
    }

    @Test
    @DisplayName("an invitation grants at least one capability and carries a SHA-256 hash")
    void anInvitationIsWellFormed() {
        // A grant that confers nothing is not a grant: the collaborator would be told
        // they are on a campaign they cannot touch. The database cannot see the
        // absence of capability rows, so this is the check that holds.
        assertThatThrownBy(() -> invitation(EnumSet.noneOf(Capability.class)))
                .isInstanceOf(IllegalArgumentException.class);

        Collaborator collaborator = accepted(EnumSet.of(Capability.EDIT_BASICS));
        assertThatThrownBy(() -> collaborator.changeCapabilities(EnumSet.noneOf(Capability.class)))
                .isInstanceOf(IllegalArgumentException.class);

        // Anything other than a 32-byte digest means the caller hashed with something
        // else, or did not hash at all — and the second of those would store the
        // working link.
        assertThatThrownBy(() -> Collaborator.invite(
                        Identifiers.newIdentifier(),
                        EmailAddress.of("collaborator@example.com"),
                        new byte[16],
                        Identifiers.newIdentifier(),
                        EnumSet.of(Capability.EDIT_BASICS),
                        NOW,
                        NOW.plus(WEEK)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> invitation(EnumSet.of(Capability.EDIT_BASICS))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("changing capabilities replaces the set rather than adding to it")
    void changingCapabilitiesReplaces() {
        Collaborator collaborator = accepted(EnumSet.of(Capability.EDIT_BASICS, Capability.EDIT_STORY));

        collaborator.changeCapabilities(EnumSet.of(Capability.EDIT_STORY));

        // A merge would leave no way to express unchecking a box, and the creator
        // would have to revoke and re-invite to take a capability away.
        assertThat(collaborator.getCapabilities()).containsExactly(Capability.EDIT_STORY);
    }
}
