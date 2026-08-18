/**
 * Project updates, comments, and the signals backers leave on a project.
 *
 * <p><strong>Updates are built (#83)</strong>: §4.4's numbered Updates tab and §4.7's
 * CD-12, public or backers-only, with scheduling. Comments (C-01 to C-05), reports,
 * blocks, saves, and follows are not, and each is its own issue.
 *
 * <p>The module reads the project module through {@code project.application} —
 * {@code ProjectAccess} for who may publish, {@code PublicProjects} for whether a
 * campaign may be read at all — and never names a {@code Project}. That is the boundary
 * {@code az/ideanest/package-info.java} states and {@code ModuleBoundaryTests} checks.
 */
package az.ideanest.community;
