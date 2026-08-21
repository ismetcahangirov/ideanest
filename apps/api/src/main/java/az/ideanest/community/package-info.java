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
 * <p><strong>Saving and following are built (#90)</strong>: §4.9's C-09 and C-10, behind
 * {@code BackerSignalController}'s four writes and two lists. Two link tables, no states,
 * withdrawal by deletion — {@code V32} argues the shape. What they are <em>for</em>, beyond
 * the reader's own two lists, is {@code CommunityProjectAudiences}: the module publishes
 * {@code SAVERS} and {@code FOLLOWERS} through {@code shared.audience}, which is the half of
 * #245 that could not be written until these rows existed, and it is what finally gives
 * §4.10's "followed creator launched" and "saved project ending soon" an audience.
 *
 * <p><strong>Bulk messaging is built (#98)</strong>: §4.7's CD-13, behind
 * {@code CampaignMessageController}. A creator writes to every backer of a campaign or to a
 * saved segment of them, rate limited per campaign and audited. It renders as §4.10's "direct
 * message" rather than as a new row in that table — {@code NotificationEvents.CampaignMessageSent}
 * argues that reading — so C-12's other half, a backer replying, remains unbuilt: there is no
 * conversation here, only the creator's direction of one.
 *
 * <p>C-04 (reactions), C-05 (comments on an update), C-08 (blocking), the reply half of C-12,
 * and the rest of §4.9 are not built, and each is its own issue. C-11, launch reminders, is
 * built and lives in the project module rather than here — a reminder is collected by a
 * pre-launch page and is the one signal of the three that can come from somebody with no
 * account, which is what ties it to the campaign's lifecycle instead of to a reader's list.
 *
 * <p>Who may publish is asked through {@code shared.access.ProjectAuthorisation} for
 * {@code PUBLISH_UPDATES} — the published contract, so that the capability can be named
 * without naming the enum that decides it. Whether a campaign may be read at all comes
 * from {@code project.application.PublicProjects}. Either way this module never names a
 * {@code Project}, which is the boundary {@code az/ideanest/package-info.java} states
 * and {@code ModuleBoundaryTests} checks.
 */
package az.ideanest.community;
