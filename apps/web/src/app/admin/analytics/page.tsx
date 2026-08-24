import type { Metadata } from 'next';
import { PlatformAnalyticsView } from '../../../components/admin/PlatformAnalyticsView';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * §4.11's AD-13: volume, success rate, average pledge — issue #313.
 *
 * <p>Not the campaign dashboard, which is #95's and answers a creator about their own
 * campaign. This is the same daily rollups summed the other way, which is what made #313
 * unblockable without a new table.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Platform analytics',
  description: 'Volume, success rate and average pledge across the whole platform.',
});

export default function AnalyticsPage() {
  return (
    <div className="max-w-[920px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Platform analytics
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Three of AD-13's five. Cohorts and funnels are not here, and the screen says what each is
        waiting on rather than approximating two numbers that would look like them.
      </p>

      <div className="mt-8">
        <PlatformAnalyticsView />
      </div>
    </div>
  );
}
