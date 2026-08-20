import type { components } from '@ideanest/api-client';
import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { Money } from '../money';

/**
 * §4.7's CD-02: what a campaign took, day by day — the trend the charts panel draws.
 *
 * Read from `GET /v1/projects/{id}/analytics`, which is #95's pre-aggregated rollup rather
 * than a scan of pledges. Two consequences the screen has to respect and this module makes
 * explicit:
 *
 * - **The series is sparse.** A day on which the campaign took nothing has no entry at all.
 *   Every day carries its own running total for exactly that reason, so a line is read off
 *   `cumulativeAmount` and never accumulated across the array.
 * - **It is as fresh as the last rollup**, which is why `computedAt` comes back and why the
 *   panel prints it. A stalled aggregator and a quiet week produce the same flat line, and
 *   that field is the only thing that tells them apart.
 */

type ContractAnalytics = components['schemas']['ProjectAnalyticsResponse'];

/** One campaign-day. */
export interface TrendDay {
  /** `YYYY-MM-DD` in the platform's calendar, which `zone` names. */
  readonly day: string;
  readonly pledgeCount: number;
  readonly amount: Money;
  readonly cumulativePledgeCount: number;
  readonly cumulativeAmount: Money;
}

/** A campaign's daily trend over a range of calendar days. */
export interface Trend {
  /** The IANA zone the days were bucketed in. Printed, so a reader never has to guess. */
  readonly zone: string;
  readonly from: string;
  readonly to: string;
  /** Absent when the range holds nothing — not the campaign's currency, which would imply zero. */
  readonly currency?: string;
  /** ISO-8601 instant of the newest rollup in the range. Absent when there are no days. */
  readonly computedAt?: string;
  /** Ascending, and sparse: a day with no pledges has no entry. */
  readonly days: readonly TrendDay[];
}

/**
 * One campaign's daily trend.
 *
 * Asks for no range, which the service reads as the last thirty days — a campaign's median
 * life on this platform, and what the chart draws.
 *
 * @throws ApiError on any refusal
 */
export async function getTrend(projectId: string, signal?: AbortSignal): Promise<Trend> {
  const response = await authorizedFetch(`/v1/projects/${encodeURIComponent(projectId)}/analytics`, {
    // Matching the service's own `no-store`: a campaign's daily takings belong to the
    // account that asked for them.
    cache: 'no-store',
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  const body = (await response.json()) as ContractAnalytics;
  return {
    zone: body.timeZone ?? 'UTC',
    from: body.from ?? '',
    to: body.to ?? '',
    currency: body.currency,
    computedAt: body.computedAt,
    days: (body.days ?? []).map((day) => ({
      day: day.day ?? '',
      pledgeCount: day.pledgeCount ?? 0,
      amount: day.amount as Money,
      cumulativePledgeCount: day.cumulativePledgeCount ?? 0,
      cumulativeAmount: day.cumulativeAmount as Money,
    })),
  };
}
