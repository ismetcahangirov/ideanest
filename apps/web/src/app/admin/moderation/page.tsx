import type { Metadata } from 'next';
import { ModerationQueue } from '../../../components/moderation/ModerationQueue';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * AD-01 and AD-02's queue, inside the console shell since #294.
 *
 * `privatePageMetadata` because an administration surface has no business in an
 * index — it emits `noindex, nofollow` and no social card. THE ROUTE IS NOT A
 * GATE: there is no role model in the schema or the access token until #295, so
 * the service refuses a caller who is not on the configured moderator list and
 * the panel renders that refusal. Anything gating here would be a second, weaker
 * copy of a check the service already makes correctly, and the two would
 * eventually disagree.
 *
 * <p><strong>It no longer declares a `<main>` of its own.</strong> It did while
 * `/admin` had no shell to be inside; `AdminArea` owns the only one on the page
 * now, and two of them is not a duplicated landmark so much as an ambiguous one —
 * assistive technology offers "jump to main" and there is more than one answer.
 * The width also comes from the shell, so the console's screens line up with each
 * other instead of each choosing a column.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Moderation queue',
  description: 'Reports waiting on platform staff, and the decisions taken on them.',
});

export default function ModerationPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Moderation queue
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Every complaint the platform has received, oldest first. Deciding a report records a
        judgement about the complaint and nothing else — suspending a campaign, banning an account
        and removing content are separate decisions with separate consequences.
      </p>

      <div className="mt-8">
        <ModerationQueue detailHrefBase="/admin/moderation" />
      </div>
    </div>
  );
}
