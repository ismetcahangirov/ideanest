import type { Metadata } from 'next';
import { BadgeManager } from '../../../../../components/admin/BadgeManager';
import { privatePageMetadata } from '../../../../../lib/seo/metadata';

/**
 * §4.11's AD-03: the editorial badge manager — issue #300.
 *
 * <p>§3.2's badge is not a flag on a campaign; it is a property of being in a collection that
 * grants one. `BadgeManager` carries the argument, and the consequence for this page is that
 * it is a list of collections rather than a list of badges.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Editorial badges',
  description: 'Which collections badge the campaigns in them.',
});

export default function BadgesPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Editorial badges
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        A campaign carries §3.2&apos;s editorial badge because it is in a collection that grants
        one — there is no badge table, and no way to badge a single campaign. Turning the grant
        on or off is therefore a decision about every campaign in the list at once.
      </p>

      <div className="mt-8">
        <BadgeManager />
      </div>
    </div>
  );
}
