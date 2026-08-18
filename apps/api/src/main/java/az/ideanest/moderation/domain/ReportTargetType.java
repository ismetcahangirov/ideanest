package az.ideanest.moderation.domain;

/**
 * What kind of thing a report is about.
 *
 * <p>A closed set here and a {@code CHECK} constraint in V23, both — the enum stops
 * the writing side inventing a spelling, and the constraint holds against a support
 * script and a bulk import. Neither is redundant with the other; see
 * {@code ContentReportSchemaTests}.
 *
 * <p><strong>One of the four still cannot be reported, and it is enumerated
 * anyway.</strong> {@code PROJECT_UPDATE} has no route: §10.2 gives an update no
 * report endpoint, and AD-09's moderation of updates is not built. {@code COMMENT}
 * was in the same position until #84 — and #102's bet paid off exactly as it was
 * argued: comments arrived, V23's check constraint already named the value, and
 * publishing {@code POST /v1/comments/{id}/report} cost a controller method, a
 * {@code ReportTargets} branch, and no migration at all.
 */
public enum ReportTargetType {

    /** A campaign. §4.9's C-06, and {@code POST /v1/projects/{id}/report}. */
    PROJECT,

    /**
     * A numbered update on a campaign. AD-09's "updates".
     *
     * <p>Nothing can write this: {@code project_updates} does not exist.
     */
    PROJECT_UPDATE,

    /**
     * A comment. §4.9's C-07, and §10.2's {@code POST /v1/comments/{id}/report}.
     *
     * <p>Written since #84. The identifier is checked against {@code comments} through
     * {@code PublicComments}, which also refuses a removed one — see that class for why
     * a tombstone is deliberately not reportable.
     */
    COMMENT,

    /** An account. AD-09's "profiles", and the target a ban is decided from. */
    USER;

    /**
     * Whether this release can accept a report about this kind of thing.
     *
     * <p>Read by {@code ReportTargets}, which is the one place that has to know —
     * an endpoint cannot check the identifier of a row in a table that does not
     * exist, and accepting a report nobody can ever look at is worse than refusing
     * it, because the reporter is shown a success.
     */
    public boolean isReportable() {
        return this != PROJECT_UPDATE;
    }
}
