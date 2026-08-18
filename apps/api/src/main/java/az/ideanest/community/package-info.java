/**
 * Project updates, comments, and the signals backers leave on a project.
 *
 * <p><strong>Updates are built (#83)</strong>: §4.4's numbered Updates tab and §4.7's
 * CD-12, public or backers-only, with scheduling.
 *
 * <p><strong>Comments are built (#84)</strong>: §4.4's Comments tab and §4.9's C-01,
 * C-02 and C-03 — a two-level thread, the campaign's own replies marked from the
 * authorisation in force when they were written, and removal as a tombstone so that a
 * deleted comment never orphans the replies under it. C-07, reporting a comment, is
 * served by the moderation module through {@code PublicComments}: this module answers
 * whether a comment is there to be complained about, and #102 owns the queue.
 *
 * <p>C-04 (reactions), C-05 (comments on an update), C-08 (blocking), C-09, C-10 and
 * the rest of §4.9 are not built, and each is its own issue.
 *
 * <p>Who may publish is asked through {@code shared.access.ProjectAuthorisation} for
 * {@code PUBLISH_UPDATES} — the published contract, so that the capability can be named
 * without naming the enum that decides it. Whether a campaign may be read at all comes
 * from {@code project.application.PublicProjects}. Either way this module never names a
 * {@code Project}, which is the boundary {@code az/ideanest/package-info.java} states
 * and {@code ModuleBoundaryTests} checks.
 */
package az.ideanest.community;
