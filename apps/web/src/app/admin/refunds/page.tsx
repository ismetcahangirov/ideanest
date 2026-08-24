import type { Metadata } from 'next';
import { RefundConsole } from '../../../components/admin/RefundConsole';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * §4.11's AD-06: full and partial refunds with reason codes — issues #67 and #307.
 *
 * <p>The screen opens on the refunds that are still `REQUESTED`, because a refund stuck in
 * that state is one the platform decided on and did not complete — which means somebody is
 * waiting for money and nothing else on the console would say so.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Refunds',
  description: 'Full and partial refunds with reason codes, and every one the platform has issued.',
});

export default function RefundsPage() {
  return (
    <div className="max-w-[920px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Refunds
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Reading this list needs VIEW_FINANCE; issuing one needs ISSUE_REFUND. The mistakes are not
        comparable, which is why they are two different authorities.
      </p>

      <div className="mt-8">
        <RefundConsole />
      </div>
    </div>
  );
}
