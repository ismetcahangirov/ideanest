import type { Metadata } from 'next';
import { ModerationQueue } from '../../../components/moderation/ModerationQueue';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * The first route under `/admin`, and it is staff-only.
 *
 * `privatePageMetadata` because an administration surface has no business in an
 * index — it emits `noindex, nofollow` and no social card. THE ROUTE IS NOT A
 * GATE: there is no role model in the schema or the access token until epic
 * #100, so the service refuses a caller who is not on the configured moderator
 * list and the panel renders that refusal. Anything gating here would be a
 * second, weaker copy of a check the service already makes correctly, and the
 * two would eventually disagree.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Moderation queue',
  description: 'Reports waiting on platform staff, and the decisions taken on them.',
});

export default function ModerationPage() {
  return (
    <main className="mx-auto w-full max-w-[880px] px-5 py-10 sm:px-6 sm:py-14">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Moderation queue
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Every complaint the platform has received, oldest first. Deciding a report records a
        judgement about the complaint and nothing else — suspending a campaign, banning an account
        and removing content are separate decisions with separate consequences.
      </p>

      <div className="mt-8">
        <ModerationQueue />
      </div>
    </main>
  );
}
