/**
 * What a campaign is called, asked from outside the module that owns it.
 *
 * <p>The third package of this kind and the same shape as the two before it.
 * {@code shared.access} publishes "may this account do this"; {@code shared.audience}
 * publishes "who are these people"; this publishes "what is this campaign called, and where
 * does it live". None of them decides anything — the answer is the project module's, because
 * {@code projects} is its table — and each exists so that a caller depends on the question
 * rather than on the class that answers it.
 *
 * <p>The problem it solves is #249's. Every notification the platform sends about a campaign
 * referred to it as "this campaign", because {@code notifications.params} had no title in it
 * and nowhere for one to come from: the events behind those translations carry identifiers
 * and money, and none of them carries a title. The alternative — looking the title up in the
 * sender — is worse in a way that only shows on the messages that matter most, and
 * {@link az.ideanest.shared.project.ProjectSummaries} says why.
 */
package az.ideanest.shared.project;
