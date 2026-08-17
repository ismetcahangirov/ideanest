package az.ideanest.pledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.pledge.application.PublicBacker;
import az.ideanest.pledge.application.PublicBackers;
import az.ideanest.pledge.domain.PledgeState;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which pledges a campaign publishes as backings, and what a published backer may be.
 *
 * <p>No database and no Spring: both of these are decisions rather than queries, and a
 * suite that started a PostgreSQL container to assert on a set literal would make the
 * whole build slower for no coverage.
 */
class PublicBackingStatesTests {

    @Test
    @DisplayName("a public backing is an active pledge that is no longer a draft")
    void countedIsTheActiveSetWithoutDraft() {
        // Stated independently rather than derived from the same expression the
        // production code uses, which is the arrangement PledgeState.ACTIVE already has
        // against pledges_project_backer_active_key: two statements of one rule,
        // checked against each other, is worth more than one statement and a
        // derivation that cannot disagree because nobody reads it.
        assertThat(PublicBackers.COUNTED)
                .containsExactlyInAnyOrder(
                        PledgeState.CONFIRMED,
                        PledgeState.CHARGE_PENDING,
                        PledgeState.CHARGE_FAILED,
                        PledgeState.COLLECTED,
                        PledgeState.FULFILLED);
    }

    @Test
    @DisplayName("the counted set is the active set and the one deliberate difference")
    void countedTracksTheActiveSet() {
        // The property that has to survive #56 and epic #59 adding transitions: a state
        // that becomes active must become a public backing too, unless somebody decides
        // otherwise here and says why. DRAFT is the only decision taken so far -- a
        // five-minute reservation is not a commitment, and counting one would publish
        // that a named person is mid-checkout.
        Set<PledgeState> expected = EnumSet.copyOf(PledgeState.ACTIVE);
        expected.remove(PledgeState.DRAFT);

        assertThat(PublicBackers.COUNTED).isEqualTo(expected);
        assertThat(PublicBackers.COUNTED).doesNotContain(PledgeState.DRAFT);
    }

    @Test
    @DisplayName("a pledge that has ended is not a backing")
    void endedPledgesAreNotCounted() {
        // The six states in which the commitment between this backer and this campaign
        // is over. A campaign that went on counting a refunded pledge would be
        // advertising money it gave back.
        assertThat(PublicBackers.COUNTED)
                .doesNotContain(
                        PledgeState.EXPIRED,
                        PledgeState.CANCELED_BY_BACKER,
                        PledgeState.CANCELED_BY_PROJECT,
                        PledgeState.DROPPED,
                        PledgeState.REFUNDED,
                        PledgeState.CHARGEBACK);
    }

    @Test
    @DisplayName("the counted set cannot be edited by whoever reads it")
    void countedIsNotWritable() {
        // A public static Set that a caller could add to is a rule any bean could
        // change for the whole process at start-up.
        assertThatThrownBy(() -> PublicBackers.COUNTED.add(PledgeState.DRAFT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a named backer without a name is refused rather than published as a blank")
    void namedRequiresAnIdentity() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        // The failure PL-12 exists to prevent, arriving by the other door: a client
        // that received {"isAnonymous": false, "name": null} would render a blank where
        // a person should be, and nothing would report it.
        assertThatThrownBy(() -> new PublicBacker.Named(UUID.randomUUID(), null, "somebody", now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PublicBacker.Named(null, "Somebody", "somebody", now))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("an anonymous backer has exactly one thing on it")
    void anonymousCarriesNothingIdentifying() {
        // Not an assertion about a null field -- an assertion that there is no field.
        // If a later change adds an identifier to this record, this is what fails, and
        // it fails before anything can serialise one.
        assertThat(PublicBacker.Anonymous.class.getRecordComponents()).hasSize(1);
        assertThat(PublicBacker.Anonymous.class.getRecordComponents()[0].getName()).isEqualTo("backedAt");

        // And the hierarchy is closed, so PublicBackerListResponse's switch over it is
        // exhaustive rather than exhaustive-so-far.
        assertThat(PublicBacker.class.isSealed()).isTrue();
        assertThat(PublicBacker.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(PublicBacker.Named.class, PublicBacker.Anonymous.class);
    }
}
