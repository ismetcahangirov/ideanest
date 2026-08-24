/**
 * Who works here and what they may do — §4.11's role model, issue #295.
 *
 * <p><strong>Its own module, and the move is the point.</strong> Staff identity lived in
 * {@code project.application.ModeratorDirectory} for as long as it was one configured
 * list of addresses that decided who could approve a campaign. It is now a table of
 * grants, a vocabulary of capabilities and a policy mapping one to the other — and none
 * of that is a fact about campaigns. A member of finance who may issue a refund and may
 * not open a submission queue has nothing to do with {@code projects}, and leaving the
 * role model there would have made every future capability a change to the project
 * module.
 *
 * <p>Nothing outside this module names {@code StaffRole}. Callers ask
 * {@code shared.access.PlatformStaff} for a capability, which is the interface that made
 * this move a change to one implementation rather than to every module that needed to
 * know who is staff.
 */
package az.ideanest.staff;
