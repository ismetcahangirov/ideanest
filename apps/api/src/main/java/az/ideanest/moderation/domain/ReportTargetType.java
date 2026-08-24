package az.ideanest.moderation.domain;

/**
 * What kind of thing a report is about.
 *
 * <p>A closed set here and a {@code CHECK} constraint in V23, both — the enum stops
 * the writing side inventing a spelling, and the constraint holds against a support
 * script and a bulk import. Neither is redundant with the other; see
 * {@code ContentReportSchemaTests}.
 *
 * <p><strong>All four can now be reported, and #102's bet paid off twice.</strong>
 * {@code COMMENT} had no route until #84 and {@code PROJECT_UPDATE} had none until
 * #297, and both arrived the same way: V23's check constraint already named the
 * value, so publishing the route cost a controller method, a {@code ReportTargets}
 * branch, and no migration at all. Enumerating a target before it could be reported
 * is what made that true, and it is why nothing was removed from this file when it
 * became reachable.
 */
public enum ReportTargetType {

    /** A campaign. §4.9's C-06, and {@code POST /v1/projects/{id}/report}. */
    PROJECT,

    /**
     * A numbered update on a campaign. AD-09's "updates", and §10.2's
     * {@code POST /v1/updates/{id}/report}.
     *
     * <p>Written since #297. The identifier is checked through
     * {@code PublicProjectUpdates}, which also refuses one that is scheduled rather
     * than published — see that class for why a future update is deliberately not
     * reportable.
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
     * <p><strong>True for all four since #297</strong>, and the method is kept rather
     * than deleted. It is the thing that made adding a target cheap: a surface whose
     * table does not exist yet is enumerated, refused here, and becomes reachable by
     * flipping one answer — where an enum that only listed what worked would need a
     * migration to grow. The next target the platform learns to moderate will start
     * out returning false from this method.
     *
     * <p>Read by {@code ReportTargets}, which is the one place that has to know:
     * accepting a report nobody can ever look at is worse than refusing it, because
     * the reporter is shown a success.
     */
    public boolean isReportable() {
        return true;
    }
}
