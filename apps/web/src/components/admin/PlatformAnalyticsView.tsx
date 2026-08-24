'use client';

import { useState } from 'react';
import { InlineAlert, Pill, Skeleton, SkeletonGroup, StatBlock, StatRow } from '@ideanest/ui';
import { peakVolume, readPlatformAnalytics } from '../../lib/admin/platform-analytics';
import { formatMoney } from '../../lib/money';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the platform figures';

/** The windows somebody actually reads a platform dashboard over. */
const WINDOWS = [
  { label: '30 days', days: 30 },
  { label: '90 days', days: 90 },
  { label: '365 days', days: 365 },
] as const;

/**
 * §4.11's AD-13: volume, success rate, average pledge — issue #313.
 *
 * <h2>Three of the five, and the screen names the other two</h2>
 *
 * AD-13 lists "volume, success rate, average pledge, cohorts, funnels". The first three are
 * here. Cohorts and funnels are not, and the reasons come from the service in `notBuilt` rather
 * than being written into this file — a cohort needs a rollup keyed on a backer&apos;s first
 * pledge and a funnel needs the visit-to-pledge path, and neither exists.
 *
 * Approximating them from what is here would put two numbers on a dashboard that look like
 * cohorts and funnels and are not, which is the failure the console index avoids for its
 * blocked modules by naming what each is waiting on.
 *
 * <h2>One currency, and the exclusion is on the screen</h2>
 *
 * §21.2 refuses to convert between currencies for anything that moves money, and a figure a
 * director reads is not exempt. Pledges in another currency are counted and reported rather
 * than folded in — a total with a silent exclusion is worse than one with a stated gap.
 *
 * <h2>The chart is CSS, not a charting library</h2>
 *
 * Bars sized by percentage width, and nothing imported. A charting library in a
 * `transpilePackages` graph lands in the shared chunk for every console route — `MinimalShell`
 * records what that cost the last time — and this is one screen with one series.
 */
export function PlatformAnalyticsView() {
  const [days, setDays] = useState(30);

  const analytics = useConsoleResource(
    (signal) => {
      const to = new Date();
      const from = new Date(to.getTime() - (days - 1) * 86_400_000);
      return readPlatformAnalytics(
        from.toISOString().slice(0, 10),
        to.toISOString().slice(0, 10),
        signal,
      );
    },
    SUBJECT,
    [days],
  );

  if (analytics.status === 'signed-out' || analytics.status === 'forbidden') {
    return <ConsoleRefusal status={analytics.status} subject={SUBJECT} />;
  }

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-center gap-2">
        {WINDOWS.map((window) => (
          <Pill
            key={window.days}
            variant={days === window.days ? 'outline' : 'ghost'}
            size="sm"
            onClick={() => setDays(window.days)}
          >
            {window.label}
          </Pill>
        ))}
      </div>

      {analytics.status === 'loading' && (
        <SkeletonGroup label="Loading the platform figures">
          <Skeleton height="1rem" width="40%" />
          <Skeleton height="6rem" width="100%" className="mt-4" />
        </SkeletonGroup>
      )}

      {analytics.status === 'failed' && (
        <>
          <InlineAlert variant="danger" title="Something went wrong">
            {analytics.error}
          </InlineAlert>
          <Pill variant="ghost" size="sm" onClick={analytics.reload}>
            Try again
          </Pill>
        </>
      )}

      {analytics.status === 'ready' && analytics.data !== null && (
        <>
          <section aria-labelledby="totals-heading">
            <h2 id="totals-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
              Over {analytics.data.from} to {analytics.data.to}
            </h2>

            <StatRow className="mt-4 flex flex-wrap gap-x-10 gap-y-4">
              <StatBlock label="Volume" value={formatMoney(analytics.data.totals.volume)} />
              <StatBlock label="Pledges" value={String(analytics.data.totals.pledgeCount)} />
              <StatBlock label="Backers" value={String(analytics.data.totals.backerCount)} />
              <StatBlock
                label="Average pledge"
                value={formatMoney(analytics.data.totals.averagePledge)}
              />
              <StatBlock label="Live campaigns" value={String(analytics.data.totals.liveProjects)} />
            </StatRow>

            {analytics.data.totals.otherCurrencyPledges > 0 && (
              <InlineAlert variant="warning" title="Some pledges are not in these totals" className="mt-4">
                {analytics.data.totals.otherCurrencyPledges} pledges in the window were in another
                currency. §21.2 gives nothing to convert them with, so they are counted here and
                left out of the volume rather than added to a figure nobody could reconcile.
              </InlineAlert>
            )}
          </section>

          <section aria-labelledby="outcomes-heading">
            <h2 id="outcomes-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
              Campaigns that closed
            </h2>

            {analytics.data.outcomes.successRate == null ? (
              <p className="mt-2 text-sm text-white/48">
                No campaign closed in this window. That is not a success rate of nought — it is
                the absence of one, which is why nothing is shown here.
              </p>
            ) : (
              <StatRow className="mt-4 flex flex-wrap gap-x-10 gap-y-4">
                <StatBlock label="Succeeded" value={String(analytics.data.outcomes.succeeded)} />
                <StatBlock label="Did not" value={String(analytics.data.outcomes.failed)} />
                <StatBlock
                  label="Success rate"
                  value={`${Math.round(analytics.data.outcomes.successRate * 100)}%`}
                />
              </StatRow>
            )}
          </section>

          <section aria-labelledby="daily-heading">
            <h2 id="daily-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
              Daily volume
            </h2>

            {analytics.data.daily.length === 0 ? (
              <p className="mt-2 text-sm text-white/48">Nothing was pledged in this window.</p>
            ) : (
              <ul className="mt-4 flex list-none flex-col gap-1">
                {analytics.data.daily.map((point) => {
                  const peak = peakVolume(analytics.data!);
                  const value = Number(point.volume.amount);
                  const width = peak === 0 ? 0 : Math.round((value / peak) * 100);

                  return (
                    <li key={point.day} className="flex items-center gap-3 text-xs">
                      <span className="w-[5.5rem] shrink-0 text-white/40">{point.day}</span>
                      {/*
                        The bar is presentational; the figure beside it is the datum. A screen
                        reader is given the number and not a width, which is what CLAUDE.md
                        means by colour never carrying meaning on its own.
                      */}
                      <span
                        aria-hidden="true"
                        className="h-2 rounded-sm bg-[var(--lime-500)]"
                        style={{ width: `${width}%` }}
                      />
                      <span className="text-white/64">{formatMoney(point.volume)}</span>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>

          {analytics.data.notBuilt.length > 0 && (
            <section aria-labelledby="not-built-heading">
              <h2 id="not-built-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
                What this screen does not answer
              </h2>
              <ul className="mt-2 flex list-none flex-col gap-2">
                {analytics.data.notBuilt.map((reason) => (
                  <li key={reason} className="text-sm text-white/64">
                    {reason}
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}
    </div>
  );
}
