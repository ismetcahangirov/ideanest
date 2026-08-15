/**
 * Campaigns and their state machine, from draft through to fulfilment.
 *
 * <p>The sixteen states of {@code docs/architecture.md} §6.1 are
 * {@code domain.ProjectState}, and the edges between them are
 * {@code domain.ProjectStateMachine} — a table with no Spring and no database in
 * it, so the rule that decides whether a campaign may start taking money can be
 * checked without either.
 *
 * <p><strong>Exactly one thing changes a campaign's state:</strong>
 * {@code application.ProjectTransitionService}. It validates the edge, applies it,
 * and appends a row to {@code project_state_transitions}, in one transaction.
 * There is no state setter on the entity and no repository method that writes the
 * column, because an audit trail that depends on each caller remembering to write
 * it is an audit trail with holes in it.
 *
 * <p><strong>Exactly one thing decides authorisation:</strong>
 * {@code application.ProjectAccess}. It answers for two kinds of holder: the
 * creator, whose authority comes from {@code projects.creator_id} and is never a
 * row, and a collaborator, who holds a set of {@code domain.Capability} values
 * granted by somebody who held them first. A caller that needs one particular
 * capability asks for it by name; the coarse forms mean "may administer this
 * campaign at all". Nothing outside that class decides who may act.
 */
package az.ideanest.project;
