package az.ideanest.moderation.domain;

/**
 * What kind of thing a report is about.
 *
 * <p>A closed set here and a {@code CHECK} constraint in V23, both — the enum stops
 * the writing side inventing a spelling, and the constraint holds against a support
 * script and a bulk import. Neither is redundant with the other; see
 * {@code ContentReportSchemaTests}.
 *
 * <p><strong>Two of the four cannot be reported yet, and they are enumerated
 * anyway.</strong> §4.9's community module has not been built, so there is no
 * {@code comments} table and no {@code project_updates} table for an identifier to
 * be checked against — which is why §10.2's {@code POST /v1/comments/{id}/report} is
 * not published by this release. Naming them now costs one string in a constraint.
 * Adding them later costs a migration on the critical path of somebody else's epic,
 * landing in the same release as the feature that needs it.
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
     * <p>Nothing can write this: {@code comments} does not exist.
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
        return this == PROJECT || this == USER;
    }
}
