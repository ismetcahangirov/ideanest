/**
 * §5.5's post-campaign obligations, made checkable — issue #437.
 *
 * <p><strong>Its own module because it is about a campaign and owned by neither of the two
 * modules that know about one.</strong> The project module owns the campaign and its lifecycle;
 * the community module owns the updates. An obligation is a relationship between them — "has
 * this campaign published an update in the last month" — and putting it in either would mean the
 * other reached in to answer it.
 *
 * <p>It listens rather than being called. §8.3's outbox already announces both facts it needs:
 * {@code project.succeeded} opens a clock and {@code project.update_published} resets one, so
 * neither of those modules learns that this one exists.
 *
 * <p><strong>What it produces is a state and an escalation, and nothing else.</strong> No
 * automatic refund and no automatic suspension — §9.7 says a creator who cannot deliver "offers
 * a refund; the platform mediates", and an automatic suspension would be the platform
 * adjudicating a dispute it has told everybody it only mediates. A moderator decides; this
 * module puts the case in front of one.
 */
package az.ideanest.obligation;
