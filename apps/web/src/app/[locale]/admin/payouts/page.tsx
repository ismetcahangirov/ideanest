import type { Metadata } from 'next';
import { PayoutQueue } from '../../../../components/admin/PayoutQueue';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-05: the payout queue and its approvals — issues #69 and #306.
 *
 * <p>Every figure that produced the net is on the row, because this is where somebody signs
 * off money leaving the platform and a single number with a note saying "fees deducted" is
 * not something anybody can check.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Payouts',
  description: 'What each creator is owed, the hold it is under, and the signatures before it leaves.',
});

export default function PayoutsPage() {
  return (
    <div className="max-w-[920px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Payouts
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Figures are frozen when a payout is calculated and never recomputed — the payout two people
        approved has to be the one that is sent. Approving needs APPROVE_PAYOUT, which the finance
        role deliberately does not confer.
      </p>

      <div className="mt-8">
        <PayoutQueue />
      </div>
    </div>
  );
}
