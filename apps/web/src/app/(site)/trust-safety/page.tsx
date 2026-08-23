import type { Metadata } from 'next';
import Link from 'next/link';
import { StaticPage } from '../../../components/content/StaticPage';
import { publicPageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = publicPageMetadata({
  title: 'Trust and safety',
  description:
    'What IdeaNest checks before a campaign goes live, how to report one, and what happens to your money and your data.',
  path: '/trust-safety',
});

/**
 * `/trust-safety` — §4.13 WS-07, issue #292.
 *
 * <h2>The page most likely to be quoted back at us</h2>
 *
 * Somebody reads this when they are already worried, and every sentence on it is a commitment.
 * So it says what the platform does and — where it matters — what it does not: it reviews
 * campaigns, it does not verify that a creator can build what they described; it holds
 * addresses encrypted, it does not promise delivery. §5.4, §9, §17.3 and §17.4 are the
 * sections behind each claim.
 *
 * **It is not the legal pages.** Terms, privacy and cookies are WS-08 and #293, which is
 * blocked on a legal deliverable that §22 owns. Nothing here is written as though it were a
 * contract, and nothing links to a document that does not exist.
 */
export default function TrustSafetyPage() {
  return (
    <StaticPage
      title="Trust and safety"
      summary="What is checked, what is not, and what to do when something looks wrong."
    >
      <h2>Every campaign is read before it goes live</h2>
      <p>
        A campaign cannot publish itself. It is submitted, a person reviews it against the rules
        below, and it is approved, sent back for changes, or rejected with a reason. Nothing on
        the platform reaches the public without passing through that.
      </p>
      <p>
        Review is not verification. A reviewer checks that a campaign is what it says it is, is
        allowed to be funded, and is not somebody else’s work. They are not in a position to
        confirm that a creator can manufacture what they have drawn — which is why a pledge is
        described everywhere on this platform as funding an attempt.
      </p>

      <h2>What is not allowed</h2>
      <ul>
        <li>Prohibited items — things the platform does not allow to be funded at all.</li>
        <li>Misrepresentation — a campaign that states something untrue about itself.</li>
        <li>Work that is not the creator’s own, or that uses somebody else’s intellectual property.</li>
        <li>Offensive material, and content that targets people for who they are.</li>
        <li>Spam, and campaigns that exist to advertise something else.</li>
        <li>Fraud — where there is reason to believe nobody intends to deliver.</li>
      </ul>

      <h2>Reporting something</h2>
      <p>
        A campaign, a comment or an account can be reported by anybody with an IdeaNest account.
        A report goes to a moderator, not to an automatic filter: nothing disappears because
        five people objected to it, and a report is a request for a person to look.
      </p>
      <p>
        Reporting the same thing twice does not add weight — the second report returns the first
        one. Reports are not public, and the account being reported is not told who reported it.
      </p>

      <h2>Your money</h2>
      <p>
        Card details never reach IdeaNest’s own servers; payments go through the provider’s
        hosted fields. While a campaign is running your pledge is an authorisation rather than a
        charge, and a campaign that misses its goal takes nothing at all.
      </p>
      <p>
        Every payment operation is idempotent, which is the unglamorous property that matters
        most: a request that is retried because a connection dropped produces one pledge and one
        charge, not two.
      </p>

      <h2>Your account and your data</h2>
      <ul>
        <li>
          <strong className="font-medium text-white">Two-factor authentication</strong> is
          available on every account and required before a payout. Set it up under{' '}
          <Link href="/settings/security" className="text-white underline underline-offset-4">
            two-factor authentication
          </Link>
          .
        </li>
        <li>
          <strong className="font-medium text-white">Every signed-in device</strong> is listed
          under{' '}
          <Link href="/settings/sessions" className="text-white underline underline-offset-4">
            devices
          </Link>
          , and any of them can be signed out from any other.
        </li>
        <li>
          <strong className="font-medium text-white">Shipping addresses</strong> are encrypted at
          rest and are visible to a creator only for the campaign you backed.
        </li>
        <li>
          <strong className="font-medium text-white">A copy of your data</strong> is a download,
          and closing your account keeps it for thirty days before anonymising it — so a closure
          made in anger, or by somebody else, can be undone. Both are under{' '}
          <Link href="/settings/privacy" className="text-white underline underline-offset-4">
            data and closure
          </Link>
          .
        </li>
      </ul>

      <h2>What we cannot do</h2>
      <p>
        IdeaNest cannot make a campaign deliver. What it can do is keep the record honest: a
        campaign’s updates, its comments and the way to report it are all on the campaign’s own
        page, where somebody deciding whether to back it will see them.
      </p>
      <p>
        A creator can moderate comments on their own campaign, and a removed one does not vanish
        — the row stays, marked as removed. A comment thread that has been cleaned out therefore
        still looks like one, which is the point: a page with nothing on it and a page with
        twelve removals are different things, and a reader is entitled to tell them apart.
      </p>
    </StaticPage>
  );
}
