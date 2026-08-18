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
    @DisplayName("only the three surfaces that exist are reportable")
    void updatesAreNotReportableYet() {
        assertThat(ReportTargetType.PROJECT.isReportable()).isTrue();
        assertThat(ReportTargetType.USER.isReportable()).isTrue();

        // COMMENT moved with #84, which is what this assertion was written to make
        // somebody do: `comments` now exists, `PublicComments` checks an identifier
        // against it, and `POST /v1/comments/{id}/report` is published.
        assertThat(ReportTargetType.COMMENT.isReportable()).isTrue();

        // PROJECT_UPDATE has not moved and has no route to move to: §10.2 gives an
        // update no report endpoint and AD-09's moderation of updates is not built,
        // so a report accepted about one would be a queue row a moderator can act on
        // in no way. The value stays in the taxonomy and in V23's constraint for
        // #102's reason -- naming it costs one string, adding it later costs a
        // migration on somebody else's critical path.
        assertThat(ReportTargetType.PROJECT_UPDATE.isReportable()).isFalse();
    }
}
