/**
 * The community module's use cases, and its transaction boundary.
 *
 * <p>This is also where the module reaches other modules: {@code ProjectUpdateService}
 * asks {@code project.application} who may act on a campaign and whether the campaign
 * may be read at all, and never names a {@code Project}. Nothing in this package should
 * ever import another module's {@code domain} or {@code infrastructure} —
 * {@code ModuleBoundaryTests} checks it, and the reason is in
 * {@code az/ideanest/package-info.java}.
 */
package az.ideanest.community.application;
