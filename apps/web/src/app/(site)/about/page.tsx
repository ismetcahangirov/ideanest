import type { Metadata } from 'next';
import Link from 'next/link';
import { StaticPage } from '../../../components/content/StaticPage';
import { publicPageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = publicPageMetadata({
  title: 'About',
  description:
    'IdeaNest is a reward-based crowdfunding platform. Creators publish campaigns, backers pledge, and nobody is charged unless a campaign reaches its goal by its deadline.',
  path: '/about',
});

/**
 * `/about` — §4.13 WS-07, issue #292.
 *
 * <h2>Indexable, and the one page on the platform that is entirely editorial</h2>
 *
 * Along with `/how-it-works` and `/trust-safety` it is a fully static route: no fetch, no
 * session, no client boundary. It renders identically for every visitor, which is what makes
 * it cacheable at the edge and what keeps it out of the account area's shell.
 *
 * <h2>Every claim here is checkable against the specification</h2>
 *
 * The rule this page is written under: nothing is said that `docs/architecture.md` does not
 * say. §5.1 is where all-or-nothing comes from, §5.2 the fees, §17.4 the retention. A marketing
 * page that describes a platform slightly better than the platform is a page somebody quotes
 * back during a dispute.
 *
 * **No figures.** There is no honest aggregate to publish — no number of campaigns funded, no
 * total raised — and a page that invented one would be inventing the only thing on it a reader
 * could not verify.
 */
export default function AboutPage() {
  return (
    <StaticPage
      title="About IdeaNest"
      summary="A place to fund things that do not exist yet, on terms that are the same for everybody."
    >
      <p>
        IdeaNest is a reward-based crowdfunding platform. A creator publishes a campaign with a
        goal and a deadline; people who want the thing to exist pledge towards it; and if the
        goal is met by the deadline, the pledges are collected and the creator makes the thing.
        If it is not, nobody is charged at all.
      </p>

      <h2>All or nothing, and why it is not a detail</h2>
      <p>
        A partly funded campaign is worse than an unfunded one. It leaves a creator holding
        money that is not enough to finish with and backers holding a promise nobody can keep.
        So a campaign that misses its goal collects nothing, costs its creator no fee, and
        leaves every card untouched.
      </p>
      <p>
        The consequence is that a deadline means something. A campaign at 90% on its last day is
        genuinely at risk, and the platform says so rather than dressing it up.
      </p>

      <h2>What it costs</h2>
      <ul>
        <li>
          <strong className="font-medium text-white">A successful campaign</strong> pays 5% of
          what it raised, plus the payment processor’s own fee — roughly 2.5–3% and a small
          fixed amount per pledge.
        </li>
        <li>
          <strong className="font-medium text-white">An unsuccessful campaign</strong> pays
          nothing. No listing fee, no platform fee, no processing fee.
        </li>
      </ul>
      <p>
        Rates are configuration rather than code, so a category or an individual agreement can
        carry a different one. The rate above is the standard schedule.
      </p>

      <h2>A pledge is not a purchase</h2>
      <p>
        This is the sentence most worth reading twice. Backing a campaign funds an attempt. The
        creator is accountable for the attempt and for telling backers honestly how it is going;
        they are not a shop with the thing in a warehouse. Every campaign has to publish its
        risks and challenges before it can be submitted, and that section is required rather
        than encouraged.
      </p>

      <h2>Where to go next</h2>
      <p>
        <Link href="/how-it-works" className="text-white underline underline-offset-4">
          How it works
        </Link>{' '}
        walks through backing and running a campaign step by step.{' '}
        <Link href="/trust-safety" className="text-white underline underline-offset-4">
          Trust and safety
        </Link>{' '}
        covers review, reporting, and what happens to your data.
      </p>
    </StaticPage>
  );
}
