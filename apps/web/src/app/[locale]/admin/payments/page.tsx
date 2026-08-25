import type { Metadata } from 'next';
import { PaymentLogView } from '../../../../components/admin/PaymentLogView';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-05: the payment log viewer — issue #304.
 *
 * <p>Half of Finance. The other half is the ledger at `/admin/ledger`, and the two
 * are deliberately separate screens: this is what the platform asked a provider to
 * do and what the provider said, and that is what the money meant. A declined charge
 * appears here and nowhere else, because it moved nothing.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Payment log',
  description: 'Every call to a payment provider, its reference, and why the refused ones failed.',
});

export default function PaymentsPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Payment log
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Every call the platform has made to a payment provider, newest first, including
        the ones that were refused. A status never moves: a pending call that later
        settled is a second row, so four rows for one pledge is an attempt history
        rather than four payments.
      </p>

      <div className="mt-8">
        <PaymentLogView />
      </div>
    </div>
  );
}
