import type { Metadata } from 'next';
import { ReconciliationPanel } from '../../../../components/admin/ReconciliationPanel';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-05: the reconciliation — issue #106.
 *
 * <p>The third Finance screen, and the one that checks the other two. `/admin/payments` is
 * what a provider was asked and what it said, `/admin/ledger` is what moved — and this is
 * whether the two agree, whether the books sum to zero, and whether any account holds a
 * balance whose sign is impossible.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Reconciliation',
  description: 'Whether the platform’s money adds up, and what is wrong when it does not.',
});

export default function ReconciliationPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Reconciliation
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Three questions, each of which the other two would miss: do the debits equal the
        credits, does any account hold a sign it cannot, and does the ledger agree with the
        record of what moved. It runs nightly and reports rather than repairs — nothing here
        corrects anything, because the entry that would fix a discrepancy depends on which of
        a dozen things went wrong.
      </p>

      <div className="mt-8">
        <ReconciliationPanel />
      </div>
    </div>
  );
}
