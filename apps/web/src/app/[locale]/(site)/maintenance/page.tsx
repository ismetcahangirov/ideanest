import type { Metadata } from 'next';
import { FailureAction, FailureState } from '../../../../components/shell/FailureState';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * `/maintenance` — §4.13 WS-09's third failure state, issue #263.
 *
 * <h2>What it is for</h2>
 *
 * A page the edge can be pointed at during a planned outage, when the application is up and
 * the thing behind it is not: a database migration that takes the service down (§8.3's expand
 * / contract steps are exactly this), or a payment provider window. It is an ordinary route
 * rather than a mechanism — nothing in this application redirects to it — because whatever
 * performs the switch is a deployment concern and belongs with #139's environments work.
 *
 * That is the honest scope of this file, and it is written down rather than implied: shipping
 * a page with no switch in front of it is worth doing, and pretending the switch exists is
 * not.
 *
 * <h2>Why it is a 200 and why that is not a mistake</h2>
 *
 * A maintenance response should carry `503` with a `Retry-After`, and that header belongs to
 * whatever is doing the routing — it knows how long the window is and this page does not. A
 * Next route cannot honestly claim a 503 for a request it is answering perfectly well.
 * `noindex` is what stops the 200 from being the problem.
 *
 * <h2>No navigation links</h2>
 *
 * The only failure state that hides them. Offering "Browse campaigns" from a page that exists
 * because browsing campaigns is unavailable is an invitation into the outage.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Down for maintenance',
  description: 'IdeaNest is briefly unavailable while we make a change.',
});

export default function MaintenancePage() {
  return (
    <FailureState
      showLinks={false}
      title="IdeaNest is down for a short while"
      description={
        <p>
          We are making a planned change and the platform is unavailable while it finishes. No
          pledge is affected: nothing is collected during a maintenance window, and every
          campaign deadline is unchanged.
        </p>
      }
      action={<FailureAction href="/">Try the home page</FailureAction>}
    />
  );
}
