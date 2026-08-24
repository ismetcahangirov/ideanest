import type { Metadata } from 'next';
import { DisputeConsole } from '../../../components/admin/DisputeConsole';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * §4.11's AD-07: notification, evidence, outcome — issues #68 and #308.
 *
 * <p>Nothing here opens a dispute. A chargeback is somebody else's decision arriving through
 * a provider webhook, and this screen answers cases rather than making them.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Chargebacks',
  description: 'Cases a card network raised, the evidence against them, and how each ended.',
});

export default function DisputesPage() {
  return (
    <div className="max-w-[920px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Chargebacks
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Ordered by deadline, soonest first. Everything else about a dispute can be reconstructed
        from the provider afterwards; a deadline that has passed cannot, and losing by default is
        the expensive way to lose.
      </p>

      <div className="mt-8">
        <DisputeConsole />
      </div>
    </div>
  );
}
