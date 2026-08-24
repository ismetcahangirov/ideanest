package az.ideanest.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.moderation.domain.ReportReason;
import az.ideanest.moderation.domain.ReportState;
import az.ideanest.moderation.domain.ReportTargetType;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The report state machine, and the two vocabularies around it.
 *
 * <p>Deliberately a plain unit test: none of this needs a database, and starting a
 * container to assert on a pure function makes the suite slower for no coverage.
 *
 * <p>The rules asserted here are also asserted over HTTP by
 * {@code ContentReportApiTests} and held as constraints by V23. That is three places
 * for one rule and none of them is redundant: this one names the rule, the API test
 * proves the endpoint applies it, and the constraint holds against a support script
 * that never goes through either.
 */
class ReportStateTests {

    @Test
    @DisplayName("an open report can be upheld or dismissed, and nothing else")
    void openMovesToEitherResolution() {
        assertThat(ReportState.OPEN.canMoveTo(ReportState.UPHELD)).isTrue();
        assertThat(ReportState.OPEN.canMoveTo(ReportState.DISMISSED)).isTrue();

        // Re-opening in place would be one row with two decisions in it and only
        // the second visible, on the table an investigation reads.
        assertThat(ReportState.OPEN.canMoveTo(ReportState.OPEN)).isFalse();
    }

    @Test
    @DisplayName("both resolutions are terminal")
    void aDecidedReportCannotMoveAgain() {
        for (ReportState decided : ReportState.RESOLUTIONS) {
            for (ReportState target : ReportState.values()) {
                assertThat(decided.canMoveTo(target))
                        .withFailMessage("%s should not be able to move to %s", decided, target)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("a decided report offers no next move, which is what stops a client retrying")
    void allowedNextIsEmptyOnceDecided() {
        assertThat(ReportState.OPEN.allowedNext()).containsExactly(ReportState.UPHELD, ReportState.DISMISSED);
        assertThat(ReportState.UPHELD.allowedNext()).isEmpty();
        assertThat(ReportState.DISMISSED.allowedNext()).isEmpty();
    }

    @Test
    @DisplayName("OPEN is the only state that is not a resolution")
    void resolutionsAreTheTwoOutcomes() {
        assertThat(ReportState.OPEN.isResolution()).isFalse();
        assertThat(Arrays.stream(ReportState.values()).filter(ReportState::isResolution).toList())
                .containsExactlyInAnyOrderElementsOf(ReportState.RESOLUTIONS);
    }

    @Test
    @DisplayName("OTHER is the only reason that has to say what")
    void onlyOtherRequiresDetail() {
        assertThat(ReportReason.OTHER.requiresDetail()).isTrue();
        assertThat(Arrays.stream(ReportReason.values())
                        .filter(reason -> reason != ReportReason.OTHER)
                        .filter(ReportReason::requiresDetail)
                        .toList())
                .isEmpty();
    }

    @Test
    @DisplayName("all four of AD-09's surfaces are reportable")
    void everySurfaceIsReportable() {
        assertThat(ReportTargetType.PROJECT.isReportable()).isTrue();
        assertThat(ReportTargetType.USER.isReportable()).isTrue();

        // COMMENT moved with #84, which is what this assertion was written to make
        // somebody do: `comments` now exists, `PublicComments` checks an identifier
        // against it, and `POST /v1/comments/{id}/report` is published.
        assertThat(ReportTargetType.COMMENT.isReportable()).isTrue();

        // PROJECT_UPDATE moved with #297, and it moved for the same reason COMMENT did.
        // This assertion previously said `isFalse` and explained that §10.2 gave an
        // update no report endpoint and that AD-09's moderation of updates was not
        // built. Both stopped being true: #83 built `project_updates`, and #297 added
        // `PublicProjectUpdates`, a `ReportTargets` branch and
        // `POST /v1/updates/{id}/report` -- with no migration, because V23's check
        // constraint had named the value since #102.
        //
        // That is #102's bet paying off twice, and it is the reason `isReportable`
        // still exists rather than being deleted now that every value answers true:
        // the next surface the platform learns to moderate will be enumerated here
        // before it can be written, and will start out returning false.
        assertThat(ReportTargetType.PROJECT_UPDATE.isReportable()).isTrue();

        assertThat(Arrays.stream(ReportTargetType.values())
                        .filter(target -> !target.isReportable())
                        .toList())
                .isEmpty();
    }
}
