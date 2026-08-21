package az.ideanest.realtime.application;

import az.ideanest.shared.money.Money;
import java.util.UUID;

/**
 * What happened on one channel during one window, as the message that goes out.
 *
 * <p><strong>A delta, never a total</strong>, and this is the decision the whole module turns on.
 * The obvious design is to broadcast the campaign's new pledged total, and it cannot be built
 * here without reading {@code projects} — which belongs to the project module and which
 * {@code ModuleBoundaryTests} forbids. The events this module consumes carry one pledge each, so
 * what it can say is how much arrived since it last spoke.
 *
 * <p>That turns out to be the better shape anyway. A client renders the total the server sent it
 * and adds each delta, so a reader who joined mid-campaign is never shown a number that jumps
 * backwards because two messages arrived out of order; and a client that missed a window is
 * behind by that window rather than wrong, which a reload corrects.
 *
 * <p><strong>Money is a {@code Money} and therefore a string on the wire.</strong> §10.3's rule,
 * and it matters more here than almost anywhere: this value is added to a running total in a
 * browser, so a JSON number would be the one place on the platform where somebody's pledge went
 * through a double.
 *
 * @param channel which of §12.1's channels this is for, as its wire name
 * @param pledges how many pledges were confirmed in the window. Zero on a comments message
 * @param amount what they came to, or null when there were none. <strong>Null rather than
 *     zero</strong>: a window with no pledges has no currency to name one in, and a zero in an
 *     arbitrary currency is a value a client would have to know to ignore
 * @param comments how many comments were posted in the window. Zero on a counter message
 * @param latestCommentId the newest comment in the window, or null. <strong>Never the body</strong>
 *     — see {@link RealtimeAggregator} for why pushing content down this path would be pushing
 *     content that moderation may remove a second later, past a channel with no way to take it
 *     back
 */
public record ChannelWindow(String channel, int pledges, Money amount, int comments, UUID latestCommentId) {
}
