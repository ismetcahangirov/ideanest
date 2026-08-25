import type { Metadata } from 'next';
import { FeeEditor } from '../../../../components/admin/FeeEditor';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-11: platform and processing rates with exceptions — §9, issue #311.
 *
 * <p>There is no edit. A rate is a term rather than a setting, so a change closes the
 * schedule in force and opens a new one — otherwise every past payout would silently become
 * a figure nobody can reproduce.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Fees',
  description: 'Platform and processing rates, their exceptions, and every set of terms the platform has charged.',
});

export default function FeesPage() {
  return (
    <div className="max-w-[920px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Fees
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Changing a fee opens new terms beginning now. The closed ones stay on the screen, because
        "what did we charge in March" is a question §22.1 attaches a retention rule to.
      </p>

      <div className="mt-8">
        <FeeEditor />
      </div>
    </div>
  );
}
