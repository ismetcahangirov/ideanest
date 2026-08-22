package az.ideanest.pledgemanager.application;

import az.ideanest.pledgemanager.domain.Fulfilment;
import az.ideanest.shared.project.ProjectSummary;

/**
 * One parcel as its backer sees it — §4.8's PM-21.
 *
 * <p>The campaign's half comes from {@code ProjectSummaries}, like
 * {@code SavedCampaign}'s does: a backer looking at "where are my rewards" is looking
 * at a list across campaigns, and a list of pledge identifiers with no campaign names
 * on it is a list nobody can read.
 *
 * @param campaign the campaign, or null when it has been hard deleted since. Not an
 *     error — {@code ProjectSummaries} states that contract and this list must not
 *     fail because one campaign is gone
 * @param fulfilment what the creator last imported. Never null: a backer with no row
 *     yet is simply not in this list, because "no parcel information" is what the
 *     absence means and a synthesised {@code PREPARING} row would be the platform
 *     making a claim the creator has not made
 */
public record BackerFulfilment(ProjectSummary campaign, Fulfilment fulfilment) {
}
