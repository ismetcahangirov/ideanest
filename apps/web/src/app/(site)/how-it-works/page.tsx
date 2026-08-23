import type { Metadata } from 'next';
import Link from 'next/link';
import { StaticPage } from '../../../components/content/StaticPage';
import { publicPageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = publicPageMetadata({
  title: 'How it works',
  description:
    'Backing a campaign, running one, and what happens between a pledge and a parcel on IdeaNest.',
  path: '/how-it-works',
});

/**
 * `/how-it-works` — §4.13 WS-07, issue #292.
 *
 * <h2>Two audiences on one page, in the order they arrive</h2>
 *
 * Backers first, creators second. Somebody reading this page has almost always arrived from a
 * campaign they are considering, and a page that opened with "launch your idea" would answer
 * the question they did not ask. Splitting it into two routes was the alternative and it costs
 * a decision — "which of these am I?" — at the moment somebody wants a straight answer.
 *
 * <h2>It describes what is built, and says where something is not</h2>
 *
 * The section on collection describes the retry window because §5.1 defines one; it does not
 * describe a refund path, because §9.7's is administrative rather than something a backer
 * operates. A page that describes an intention as though it were a feature is the same defect
 * as a navigation entry pointing at a 404.
 */
export default function HowItWorksPage() {
  return (
    <StaticPage
      title="How it works"
      summary="What happens between finding a campaign and holding the thing it funded."
    >
      <h2>If you are backing something</h2>
      <p>
        Choose a reward tier, or pledge without one. Your card is authorised and{' '}
        <strong className="font-medium text-white">nothing is taken</strong> while the campaign
        is running — you can change your pledge, move to a different tier, add an extra, or
        cancel outright until the deadline.
      </p>
      <p>
        At the deadline one of two things happens. If the campaign reached its goal, every
        confirmed pledge is collected and the campaign moves into fulfilment. If it did not,
        nothing is collected, no fee is charged, and the stored card tokens are deleted within
        thirty days.
      </p>
      <p>
        A card can fail at collection — expired, out of funds, refused by a bank. There is a
        seven-day window in which it is retried before the pledge is given up on.
      </p>

      <h2>Then the creator asks you some questions</h2>
      <p>
        Most rewards need something the campaign page cannot know: a size, a colour, an edition,
        and somewhere to send it. That arrives as a survey, and it appears under{' '}
        <Link href="/account/surveys" className="text-white underline underline-offset-4">
          surveys
        </Link>{' '}
        in your account. Your shipping address is held separately from your answers and
        encrypted at rest, and it belongs to the pledge rather than to your account — so two
        campaigns can go to two different places.
      </p>
      <p>
        As parcels go out,{' '}
        <Link href="/account/deliveries" className="text-white underline underline-offset-4">
          deliveries
        </Link>{' '}
        shows the carrier and the tracking number for each one.
      </p>

      <h2>If you are running a campaign</h2>
      <ul>
        <li>
          <strong className="font-medium text-white">Draft it.</strong> A title, a summary, a
          category, a goal, a duration between one and sixty days, and a cover image. The story
          needs at least 500 characters and the risks section at least 200 — both are required.
        </li>
        <li>
          <strong className="font-medium text-white">Add rewards.</strong> Up to a hundred tiers,
          each with a price, what is in it, and where it ships. A tier’s price cannot change once
          the campaign is live, and a tier with backers cannot be deleted — only hidden.
        </li>
        <li>
          <strong className="font-medium text-white">Submit it for review.</strong> A person
          reads it. They approve it, ask for changes, or reject it with a reason.
        </li>
        <li>
          <strong className="font-medium text-white">Launch.</strong> From this point the goal
          and the deadline are fixed. Everything else — the story, the updates, the reward
          quantities upwards — stays editable.
        </li>
        <li>
          <strong className="font-medium text-white">Deliver.</strong> Send the survey, collect
          the addresses, lock them when you start printing labels, and record tracking as parcels
          go out.
        </li>
      </ul>

      <h2>What you are agreeing to as a backer</h2>
      <p>
        A pledge funds an attempt rather than buying a finished object. The creator owes you an
        honest account of how it is going — that is what campaign updates are for — and the
        platform owes you the tools to see it and to report a campaign that is not being honest.
        Neither of those is the same as a guarantee that the thing will arrive.
      </p>
      <p>
        <Link href="/trust-safety" className="text-white underline underline-offset-4">
          Trust and safety
        </Link>{' '}
        sets out what is checked, and what to do when something is wrong.
      </p>
    </StaticPage>
  );
}
