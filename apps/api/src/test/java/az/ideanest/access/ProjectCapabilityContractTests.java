package az.ideanest.access;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.project.domain.Capability;
import az.ideanest.shared.access.ProjectCapability;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The published vocabulary and the enum that decides, held in lockstep.
 *
 * <p><strong>The cost of publishing a contract without publishing the module.</strong>
 * {@code project.domain.Capability} is what is stored, validated by
 * {@code collaborator_capabilities_known}, and reasoned over by {@code Grants};
 * {@link ProjectCapability} is the list of names other modules may say. Two enums for
 * one idea buys the boundary and risks a drift — a capability added to one and not the
 * other, or renamed on one side, which would be a permission silently unaskable from
 * outside the module. These assertions are what makes that drift a build failure.
 *
 * <p>No database and no Spring: this is a statement about two enums, and a test that
 * started a container to make it would be slower for no coverage.
 */
class ProjectCapabilityContractTests {

    @Test
    @DisplayName("every deciding capability is published, and every published one decides something")
    void theTwoVocabulariesAreTheSame() {
        Set<String> deciding =
                Arrays.stream(Capability.values()).map(Enum::name).collect(Collectors.toSet());
        Set<String> published =
                Arrays.stream(ProjectCapability.values()).map(Enum::name).collect(Collectors.toSet());

        // Both directions. A capability the project module decides and does not publish
        // is one no other module can ever ask for, which is the whole of #236; a
        // published name nothing decides is a request that would refuse everybody.
        assertThat(deciding).isEqualTo(published);
    }

    @Test
    @DisplayName("the mapping is total and round-trips in both directions")
    void theMappingRoundTrips() {
        for (ProjectCapability published : ProjectCapability.values()) {
            Capability deciding = Capability.of(published);
            assertThat(deciding)
                    .withFailMessage("No Capability decides the published %s", published)
                    .isNotNull();
            assertThat(deciding.published()).isEqualTo(published);
        }

        for (Capability deciding : Capability.values()) {
            assertThat(Capability.of(deciding.published())).isEqualTo(deciding);
        }
    }

    @Test
    @DisplayName("the two agree constant for constant, not merely as sets")
    void thePairingIsByName() {
        // A set comparison would still pass if EDIT_STORY were paired with
        // EDIT_REWARDS' published name, which is the one drift that would not throw
        // anywhere and would hand a story writer the ability to reprice a campaign.
        for (Capability deciding : Capability.values()) {
            assertThat(deciding.published().name()).isEqualTo(deciding.name());
        }
    }
}
