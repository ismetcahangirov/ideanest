import type { Metadata } from 'next';
import { LedgerExplorer } from '../../../../components/admin/LedgerExplorer';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-05: the ledger explorer — issue #305.
 *
 * <p>The other half of Finance. `/admin/payments` is what a provider was asked and
 * what it said; this is what moved, in the double-entry form §7.2 requires — and only
 * ever when something actually did, which is why a declined charge has a row there
 * and nothing here.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Ledger',
  description: 'The double-entry ledger, by account and by campaign, with both sides of every entry.',
});

export default function LedgerPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">Ledger</h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Every posting the platform has written, both sides shown together, newest first.
        The debits equal the credits on each one — PostgreSQL refuses a transaction in
        which they do not — and each card says so rather than asking you to take it on
        trust.
      </p>

      <div className="mt-8">
        <LedgerExplorer />
      </div>
    </div>
  );
}
