/**
 * Reports, review queues, suspension, and cancellation.
 *
 * <p>What exists today is AD-02's intake and queue (#102): somebody reports a
 * campaign or a person, the report lands in {@code content_reports}, and platform
 * staff uphold or dismiss it. The state machine is deliberately small — {@code OPEN}
 * to one of two terminal outcomes — because the interesting decisions are the ones
 * either side of it: what may be reported, and what a moderator does afterwards.
 *
 * <p><strong>Deciding a report is not the same as acting on it.</strong> Upholding
 * one records that the complaint was justified; suspending the campaign, banning the
 * account, or removing the comment are separate privileged actions with their own
 * endpoints, their own state machines, and their own audit rows. Folding them
 * together would mean a moderator who agreed with a report could not say so without
 * also taking the campaign down, which is the wrong granularity for a queue whose
 * whole job is triage.
 *
 * <p><strong>Who counts as staff is not decided here.</strong> There is no role
 * model until epic #100, so this module asks {@code shared.access.PlatformStaff} —
 * behind which is the same configured list the campaign moderation endpoints check —
 * rather than keeping a second copy of it. Two lists that can disagree about who is
 * staff is a worse arrangement than one dependency that epic #100 deletes. Asked
 * through the contract rather than by naming the implementation, so that #100 replaces
 * one class and no module notices.
 */
package az.ideanest.moderation;
