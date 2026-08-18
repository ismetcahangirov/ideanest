/**
 * Aggregation of campaign metrics for creator-facing reporting.
 *
 * <p>§4.7's creator dashboard, of which one capability is built: CD-03, referral
 * attribution (#94). A visit that carried a source is recorded against an opaque
 * visitor token, held for an attribution window, and resolved to a pledge when one is
 * confirmed — <strong>last non-direct touch inside the window</strong>, which
 * {@code domain.LastNonDirectTouch} states in full and V24's header argues for.
 *
 * <p><strong>This module reads nothing that belongs to another one.</strong> It learns
 * that a pledge exists from a published event and from nowhere else:
 * {@code application.ReferralAttributionListener} takes an {@code OutboxMessage} and
 * switches on its type, which is the shape {@code ApplicationEventOutboxDispatcher}
 * prescribes. Authorisation is asked through {@code shared.access.ProjectAuthorisation}
 * for {@code VIEW_FINANCES}, which is what the referral report exposes, and a
 * campaign's public facts come through the project module's application layer as
 * {@code PublicProjects} — because that is the only part of it another module may see.
 *
 * <p><strong>Nothing publishes {@code pledge.confirmed} yet</strong>, so attribution
 * has no traffic in production. {@code application.PledgeConfirmed} says what is
 * missing, what the remaining work is, and why it is not in #94.
 *
 * <p>The rest of §4.7 — the pledge trend, the device split, the conversion rate, the
 * per-tier sales, the geography — is not built and is not stubbed here. So is §8.4's
 * {@code analytics-aggregator} and the {@code project_analytics_daily} rollup it would
 * populate: this module aggregates at read time, over one campaign's attributions,
 * which is a query small enough not to need one yet.
 */
package az.ideanest.analytics;
