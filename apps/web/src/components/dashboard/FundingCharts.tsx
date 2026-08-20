'use client';

import { useEffect, useState } from 'react';
import { InlineAlert, Skeleton, SkeletonGroup, StatBlock, StatRow } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import { formatMoney } from '../../lib/money';
import { formatRelativeTime } from '../../lib/time';
import { getTrend, type Trend } from '../../lib/dashboard/analytics';
import { getBreakdown, type BackerBreakdown } from '../../lib/dashboard/backers';
import { ShareBars, type ShareBar } from './ShareBars';
import { TrendChart } from './TrendChart';

/**
 * §4.7's CD-02, CD-07 and CD-08 — issue 96: the funding trend, the reward mix, and where
 * the backers are.
 *
 * <h2>Two reads, and they are deliberately not one</h2>
 *
 * The trend comes from `GET /analytics`, which is #95's daily rollup and is as fresh as the
 * last aggregation pass. The two splits come from `GET /backers/breakdown`, which is
 * computed from `pledges` at the moment of the request. Folding them into one endpoint
 * would put two different freshnesses in one body with nothing to tell them apart — the
 * same argument CD-01's live totals make for not joining the trend, one panel over.
 *
 * <p>They are fetched together and rendered together, and the one thing this screen must not
 * do is imply they were measured at the same instant. `computedAt` is printed under the
 * trend for that reason: a stalled aggregator and a quiet week draw the same flat line, and
 * that line beside a fresh reward mix is how somebody concludes the campaign stopped.
 *
 * <h2>A failure in one does not take the other down</h2>
 *
 * Each read has its own state. A breakdown that fails leaves the trend on screen with an
 * explanation in its place, because half a dashboard is more useful than an error page —
 * and because the two failures have different causes.
 */

type Status = 'loading' | 'ready' | 'failed';

function messageFor(cause: unknown, subject: string): string {
  if (cause instanceof ApiError) {
    if (cause.status === 401) return 'Your session has expired. Sign in again to see this campaign.';
    if (cause.status === 403) {
      return `Your collaborator grant on this campaign does not include ${subject}.`;
    }
    if (cause.status === 404) return 'That campaign does not exist, or it is not one you work on.';
  }
  return `${subject} could not be loaded. It is the service rather than your campaign — try again shortly.`;
}

export interface FundingChartsProps {
  readonly projectId: string;
  /** Injected by tests. Default to the real readers. */
  readonly loadTrend?: typeof getTrend;
  readonly loadBreakdown?: typeof getBreakdown;
  /** Injected by tests, so "computed 4 minutes ago" is assertable. */
  readonly nowImpl?: () => Date;
}

export function FundingCharts({
  projectId,
  loadTrend,
  loadBreakdown,
  nowImpl,
}: FundingChartsProps) {
  const [trendStatus, setTrendStatus] = useState<Status>('loading');
  const [trend, setTrend] = useState<Trend | null>(null);
  const [trendFailure, setTrendFailure] = useState('');

  const [splitStatus, setSplitStatus] = useState<Status>('loading');
  const [breakdown, setBreakdown] = useState<BackerBreakdown | null>(null);
  const [splitFailure, setSplitFailure] = useState('');

  useEffect(() => {
    const controller = new AbortController();

    (loadTrend ?? getTrend)(projectId, controller.signal)
      .then((body) => {
        setTrend(body);
        setTrendStatus('ready');
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setTrendFailure(messageFor(cause, 'the funding trend'));
        setTrendStatus('failed');
      });

    (loadBreakdown ?? getBreakdown)(projectId, controller.signal)
      .then((body) => {
        setBreakdown(body);
        setSplitStatus('ready');
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setSplitFailure(messageFor(cause, 'the backer breakdown'));
        setSplitStatus('failed');
      });

    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  return (
    <section aria-labelledby="charts-heading">
      <h1 id="charts-heading" className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Funding and backers
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        How this campaign has raised what it has raised, which rewards backers chose, and
        where they are.
      </p>

      <h2 className="mt-8 text-lg font-semibold text-white">Funding over time</h2>
      {trendStatus === 'loading' ? (
        <SkeletonGroup label="Loading the funding trend">
          <Skeleton className="h-[220px] w-full" />
        </SkeletonGroup>
      ) : null}
      {trendStatus === 'failed' ? <InlineAlert variant="danger">{trendFailure}</InlineAlert> : null}
      {trendStatus === 'ready' && trend !== null ? (
        trend.days.length === 0 ? (
          <p className="mt-4 max-w-[62ch] text-sm text-white/64">
            Nothing has been pledged between {trend.from} and {trend.to}. The chart appears with
            the first confirmed pledge.
          </p>
        ) : (
          <>
            <TrendChart
              days={trend.days}
              from={trend.from}
              to={trend.to}
              label={`Running total, ${trend.from} to ${trend.to} (${trend.zone})`}
            />
            {/*
              The freshness, printed rather than assumed. This series is only ever as
              current as the last rollup, and a creator comparing it against the live
              totals on the overview needs to know why the two can differ.
            */}
            {trend.computedAt !== undefined ? (
              <p className="mt-2 text-sm text-white/64">
                Aggregated {formatRelativeTime(trend.computedAt, (nowImpl ?? (() => new Date()))())}. The
                live totals on the overview are read at the moment you ask for them.
              </p>
            ) : null}
          </>
        )
      ) : null}

      <h2 className="mt-10 text-lg font-semibold text-white">Rewards and destinations</h2>
      {splitStatus === 'loading' ? (
        <SkeletonGroup label="Loading the reward and destination split">
          <Skeleton className="h-24 w-full" />
        </SkeletonGroup>
      ) : null}
      {splitStatus === 'failed' ? <InlineAlert variant="danger">{splitFailure}</InlineAlert> : null}
      {splitStatus === 'ready' && breakdown !== null ? (
        breakdown.backerCount === 0 ? (
          <p className="mt-4 max-w-[62ch] text-sm text-white/64">
            Nobody has backed this campaign yet, so there is nothing to split.
          </p>
        ) : (
          <>
            <StatRow className="mt-4">
              <StatBlock label="Backers" value={String(breakdown.backerCount)} />
              <StatBlock
                label="Pledged"
                value={breakdown.total ? formatMoney(breakdown.total) : 'Nothing yet'}
              />
            </StatRow>

            <h3 className="mt-8 text-sm font-semibold text-white">By reward tier</h3>
            {breakdown.rewards.length === 0 ? (
              <p className="mt-2 max-w-[62ch] text-sm text-white/64">
                Every backer so far pledged without taking a reward.
              </p>
            ) : (
              <>
                <ShareBars label="Backers by reward tier" rows={breakdown.rewards.map(rewardRow)} />
                {/*
                  Said rather than left to be noticed. These bars sum to at most the
                  campaign's total, and the difference is support that took no reward —
                  a creator who added them up and found a shortfall would be right.
                */}
                <p className="mt-3 max-w-[62ch] text-sm text-white/64">
                  These cover the backers who chose a tier. A pledge with no reward is not
                  listed here, which is why the tiers can add up to less than the total above.
                </p>
              </>
            )}

            <h3 className="mt-8 text-sm font-semibold text-white">By destination</h3>
            <ShareBars label="Backers by destination" rows={breakdown.countries.map(countryRow)} />
            <p className="mt-3 max-w-[62ch] text-sm text-white/64">
              A pledge that named no destination — a digital reward, or support with no reward
              — is counted under “No destination” rather than left out.
            </p>
          </>
        )
      ) : null}
    </section>
  );
}

/** A tier's row. A tier the campaign has since removed keeps its pledges and loses its name. */
function rewardRow(slice: BackerBreakdown['rewards'][number]): ShareBar {
  return {
    label: slice.title ?? 'A removed tier',
    backerCount: slice.backerCount,
    amount: slice.amount,
  };
}

/**
 * A destination's row.
 *
 * The country code as it stands, rather than a translated country name: there is no country
 * vocabulary on the platform yet — `locations` covers the eighteen Azerbaijani cities a
 * campaign can be in, and nothing maps ISO codes to names in a locale. A code a creator can
 * look up beats a name this screen would have to invent.
 */
function countryRow(slice: BackerBreakdown['countries'][number]): ShareBar {
  return {
    label: slice.country ?? 'No destination',
    backerCount: slice.backerCount,
    amount: slice.amount,
  };
}
