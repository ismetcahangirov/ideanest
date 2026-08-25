import type { Metadata } from 'next';
import { HealthDashboard } from '../../../../components/admin/HealthDashboard';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * §4.11's AD-16: queue depth, failed jobs, provider status — §18, issue #316.
 *
 * <p>#316 was labelled blocked on #138 and was not quite: every number here is a count the
 * service can already take. What it does not do is alert, and the page says so — a dashboard
 * presented as monitoring is worse than an honest gap.
 *
 * <p>`privatePageMetadata` for the reason every console route gives: these pages are
 * per-person, they are not for a crawler, and several of them name people.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'System health',
  description: 'Queue depth, failed jobs and provider status, measured when you open the page.',
});

export default function HealthPage() {
  return (
    <div className="max-w-[920px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        System health
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Measured when you open it. Nothing on this page wakes anybody, and nothing watches it while
        you are not looking — read it when you suspect something rather than relying on it to
        tell you.
      </p>

      <div className="mt-8">
        <HealthDashboard />
      </div>
    </div>
  );
}
