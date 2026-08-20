import type { components } from '@ideanest/api-client';
import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { Money } from '../money';

/**
 * What the backer report and the charts ask the service — §4.7's CD-10, CD-07, CD-08 and
 * CD-11, and issues 97, 96 and 79.
 *
 * <h2>Browser reads, like the rest of the dashboard</h2>
 *
 * `lib/api/server.ts` sends no token by design, and every route here is one campaign team's
 * view of their own backers behind a bearer token that the service answers `no-store`.
 * There is nothing to render on the server, which is the argument `lib/dashboard/api.ts`
 * already makes for the overview.
 *
 * <h2>The types are the contract's, narrowed</h2>
 *
 * springdoc marks every field optional because Java cannot tell it otherwise. These bodies
 * are serialised with the service's `non_null` default, so an absent key genuinely means
 * absent — but only for the fields that can be: a tier a pledge did not take, a destination
 * it did not name, a cursor when the page is the last one. Everything else is always
 * present, and narrowing here is what keeps the screens free of `?.` on fields that cannot
 * be missing.
 */

type ContractBacker = components['schemas']['Backer'];
type ContractList = components['schemas']['BackerListResponse'];
type ContractBreakdown = components['schemas']['BackerBreakdownResponse'];
type ContractSegment = components['schemas']['BackerSegmentResponse'];

/**
 * The five pledge states the report covers.
 *
 * Narrowed from the contract's twelve by hand, because springdoc publishes the whole
 * `PledgeState` enum — the service refuses the other seven with a 400, and a filter control
 * offering a state that cannot be asked for would be a control that only ever fails.
 */
export const REPORTED_STATES = [
  'CONFIRMED',
  'CHARGE_PENDING',
  'CHARGE_FAILED',
  'COLLECTED',
  'FULFILLED',
] as const;

export type ReportedState = (typeof REPORTED_STATES)[number];

/** One backer, as the campaign team sees them. */
export interface Backer {
  readonly pledgeId: string;
  /** The account's display name. Present even when `anonymous` — see `BackerPage` on the service. */
  readonly name: string;
  readonly email: string;
  /** Whether they asked not to be named on the public page. Never a reason to hide the name here. */
  readonly anonymous: boolean;
  /** Absent for support that took no reward. */
  readonly rewardTierId?: string;
  /** Absent for support that took no reward, and for a tier the campaign has since removed. */
  readonly rewardTitle?: string;
  readonly amount: Money;
  readonly state: ReportedState;
  /** Absent where the pledge named no destination. */
  readonly country?: string;
  /** ISO-8601 instant. */
  readonly backedAt: string;
}

/** One page of the report. */
export interface BackerPage {
  readonly backers: readonly Backer[];
  /** Send back as `?cursor=`. Absent on the last page. */
  readonly nextCursor?: string;
  /** How many the filter matches on the campaign, not on this page. */
  readonly matched: number;
  /** Absent when nothing matched. */
  readonly currency?: string;
}

/** The four axes the report filters on. Empty means "any", never "none". */
export interface BackerFilter {
  readonly states: readonly ReportedState[];
  readonly rewardTierIds: readonly string[];
  readonly countries: readonly string[];
  readonly term: string;
}

/** No filter at all: the whole campaign. */
export const NO_FILTER: BackerFilter = {
  states: [],
  rewardTierIds: [],
  countries: [],
  term: '',
};

/** Whether a filter narrows anything, which is what decides if it is worth saving. */
export function isNarrowed(filter: BackerFilter): boolean {
  return (
    filter.states.length > 0 ||
    filter.rewardTierIds.length > 0 ||
    filter.countries.length > 0 ||
    filter.term.trim() !== ''
  );
}

/** One reward tier's share of the campaign — CD-07. */
export interface RewardSlice {
  readonly rewardTierId: string;
  /** Absent for a tier the campaign has since removed, whose pledges remain. */
  readonly title?: string;
  readonly price?: Money;
  readonly backerCount: number;
  readonly amount: Money;
}

/** One destination's share — CD-08. `country` absent means "named no destination". */
export interface CountrySlice {
  readonly country?: string;
  readonly backerCount: number;
  readonly amount: Money;
}

/** What the campaign sold and where it is going. */
export interface BackerBreakdown {
  /** Absent on a campaign nobody has backed. */
  readonly currency?: string;
  readonly backerCount: number;
  readonly total?: Money;
  readonly rewards: readonly RewardSlice[];
  readonly countries: readonly CountrySlice[];
}

/** A filter with a name, saved against the campaign. */
export interface BackerSegment {
  readonly id: string;
  readonly name: string;
  readonly filter: BackerFilter;
  readonly createdBy: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** The file the export answered with, and what it says about itself. */
export interface BackerExport {
  readonly filename: string;
  readonly csv: string;
  readonly rows: number;
  /** Whether the row cap was reached, so the file is short. Never inferred from the row count. */
  readonly truncated: boolean;
}

/**
 * One page of the campaign's backers.
 *
 * @param segmentId a saved segment to apply instead of `filter`. The service resolves it,
 *   so a segment edited in another tab changes what this returns — which is the point of
 *   saving one
 * @throws ApiError on any refusal
 */
export async function listBackers(
  projectId: string,
  options: {
    readonly filter?: BackerFilter;
    readonly segmentId?: string;
    readonly cursor?: string;
    readonly size?: number;
    readonly signal?: AbortSignal;
  } = {},
): Promise<BackerPage> {
  const query = new URLSearchParams();
  if (options.segmentId !== undefined) {
    query.set('segment', options.segmentId);
  } else if (options.filter !== undefined) {
    for (const state of options.filter.states) query.append('state', state);
    for (const tier of options.filter.rewardTierIds) query.append('rewardTier', tier);
    for (const country of options.filter.countries) query.append('country', country);
    if (options.filter.term.trim() !== '') query.set('q', options.filter.term.trim());
  }
  if (options.cursor !== undefined) query.set('cursor', options.cursor);
  if (options.size !== undefined) query.set('size', String(options.size));

  const search = query.toString();
  const response = await authorizedFetch(
    `/v1/projects/${encodeURIComponent(projectId)}/backers${search === '' ? '' : `?${search}`}`,
    // The service answers `no-store` and this body is a campaign's mailing list. A cached
    // copy is one the browser keeps after the tab that was allowed to read it is gone.
    { cache: 'no-store', signal: options.signal },
  );
  if (!response.ok) throw await errorFrom(response);

  const body = (await response.json()) as ContractList;
  return {
    backers: (body.backers ?? []).map(backerOf),
    nextCursor: body.nextCursor,
    matched: body.matched ?? 0,
    currency: body.currency,
  };
}

/**
 * The campaign's reward mix and destinations — the two charts.
 *
 * Takes no filter, deliberately: the splits describe the campaign rather than the current
 * search, and a chart that moved when a filter did would read as the campaign changing.
 *
 * @throws ApiError on any refusal
 */
export async function getBreakdown(
  projectId: string,
  signal?: AbortSignal,
): Promise<BackerBreakdown> {
  const response = await authorizedFetch(
    `/v1/projects/${encodeURIComponent(projectId)}/backers/breakdown`,
    { cache: 'no-store', signal },
  );
  if (!response.ok) throw await errorFrom(response);

  const body = (await response.json()) as ContractBreakdown;
  return {
    currency: body.currency,
    backerCount: body.backerCount ?? 0,
    total: body.total as Money | undefined,
    rewards: (body.rewards ?? []) as readonly RewardSlice[],
    countries: (body.countries ?? []) as readonly CountrySlice[],
  };
}

/** Every segment saved against the campaign, newest first. @throws ApiError on any refusal */
export async function listSegments(
  projectId: string,
  signal?: AbortSignal,
): Promise<readonly BackerSegment[]> {
  const response = await authorizedFetch(
    `/v1/projects/${encodeURIComponent(projectId)}/backer-segments`,
    { cache: 'no-store', signal },
  );
  if (!response.ok) throw await errorFrom(response);

  const body = (await response.json()) as readonly ContractSegment[];
  return body.map(segmentOf);
}

/**
 * Saves the current filter under a name.
 *
 * @throws ApiError on any refusal. 409 is a name the campaign already uses, compared folded,
 *   or a campaign already holding as many segments as the report will
 */
export async function saveSegment(
  projectId: string,
  name: string,
  filter: BackerFilter,
): Promise<BackerSegment> {
  const response = await authorizedFetch(
    `/v1/projects/${encodeURIComponent(projectId)}/backer-segments`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, filter: bodyOf(filter) }),
    },
  );
  if (!response.ok) throw await errorFrom(response);

  return segmentOf((await response.json()) as ContractSegment);
}

/** Forgets a segment. @throws ApiError on any refusal */
export async function deleteSegment(projectId: string, segmentId: string): Promise<void> {
  const response = await authorizedFetch(
    `/v1/projects/${encodeURIComponent(projectId)}/backer-segments/${encodeURIComponent(segmentId)}`,
    { method: 'DELETE' },
  );
  if (!response.ok) throw await errorFrom(response);
}

/**
 * Exports the matching backers as a CSV file.
 *
 * A POST because that is what §10.2 gives this route: the export is audited, and a GET that
 * writes an audit row is one a browser may prefetch. The file comes back as text rather than
 * as a download the browser handles on its own — the caller decides when to offer it, and
 * `truncated` has to be read before it does.
 *
 * @throws ApiError on any refusal. 429 is the per-account export budget
 */
export async function exportBackers(
  projectId: string,
  options: { readonly filter?: BackerFilter; readonly segmentId?: string } = {},
): Promise<BackerExport> {
  const response = await authorizedFetch(
    `/v1/projects/${encodeURIComponent(projectId)}/backers/export`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(
        options.segmentId !== undefined
          ? { segmentId: options.segmentId }
          : { filter: bodyOf(options.filter ?? NO_FILTER) },
      ),
    },
  );
  if (!response.ok) throw await errorFrom(response);

  return {
    filename: filenameOf(response.headers.get('Content-Disposition')),
    csv: await response.text(),
    rows: Number(response.headers.get('X-Export-Rows') ?? '0'),
    // Compared against the string rather than coerced: `Boolean('false')` is true, which
    // would report every export as short.
    truncated: response.headers.get('X-Export-Truncated') === 'true',
  };
}

/** The filter as the request body shape. Empty axes are omitted, which the service reads as "any". */
function bodyOf(filter: BackerFilter): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  if (filter.states.length > 0) body.states = filter.states;
  if (filter.rewardTierIds.length > 0) body.rewardTierIds = filter.rewardTierIds;
  if (filter.countries.length > 0) body.countries = filter.countries;
  if (filter.term.trim() !== '') body.term = filter.term.trim();
  return body;
}

function backerOf(backer: ContractBacker): Backer {
  return {
    pledgeId: backer.pledgeId ?? '',
    name: backer.name ?? '',
    email: backer.email ?? '',
    anonymous: backer.anonymous ?? false,
    rewardTierId: backer.rewardTierId,
    rewardTitle: backer.rewardTitle,
    amount: backer.amount as Money,
    state: (backer.state ?? 'CONFIRMED') as ReportedState,
    country: backer.country,
    backedAt: backer.backedAt ?? '',
  };
}

function segmentOf(segment: ContractSegment): BackerSegment {
  return {
    id: segment.id ?? '',
    name: segment.name ?? '',
    filter: {
      states: (segment.filter?.states ?? []) as readonly ReportedState[],
      rewardTierIds: segment.filter?.rewardTierIds ?? [],
      countries: segment.filter?.countries ?? [],
      term: segment.filter?.term ?? '',
    },
    createdBy: segment.createdBy ?? '',
    createdAt: segment.createdAt ?? '',
    updatedAt: segment.updatedAt ?? '',
  };
}

/**
 * The filename the service chose.
 *
 * Parsed rather than reconstructed here, so that the name in the creator's downloads folder
 * is the one the audit row describes. A header that is missing or unparseable falls back to
 * something safe rather than to `undefined`, which would save the file as the route.
 */
function filenameOf(disposition: string | null): string {
  const match = disposition?.match(/filename="?([^";]+)"?/i);
  return match?.[1] ?? 'backers.csv';
}
