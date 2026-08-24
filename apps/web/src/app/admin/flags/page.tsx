import type { Metadata } from 'next';
import { FlagConsole } from '../../../components/admin/FlagConsole';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * §4.11's AD-12: gradual rollout and experiments — issue #312.
 *
 * <p>Off is off for everybody, including the accounts named explicitly on a flag. That is the
 * property somebody relies on when they reach for this during an incident.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Feature flags',
  description: 'Gradual rollout and experiments, with a kill switch that means what it says.',
});

export default function FlagsPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Feature flags
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        A rollout percentage is decided by a stable hash, so widening one only ever adds people —
        nobody loses a feature because the number went up. Switching a flag off overrides
        everything else about it.
      </p>

      <div className="mt-8">
        <FlagConsole />
      </div>
    </div>
  );
}
