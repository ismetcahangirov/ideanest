import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { ProjectState } from '../projects/api';

/**
 * §4.11's campaign directory: what campaigns exist — issue #387.
 *
 * <h2>Why the console needed one</h2>
 *
 * <p>Three screens could reach a campaign and all three start from something the
 * campaign did: a report somebody filed about it, the submission queue it entered by
 * being submitted, and a suspension that takes an identifier a member of staff already
 * had. A campaign that is simply a draft, or simply live, or that was approved a week
 * ago and has been sitting unlaunched since, was in none of them — and the only way to
 * look one up was a psql session.
 *
 * <h2>Not the submission queue with the filter widened</h2>
 *
 * <p>`/v1/admin/moderation/submissions` is a queue: oldest first, keyed on the
 * transition that put each campaign in its state, and refusing any state that is not
 * one a moderator decides. This is a directory: newest first, every state, and the
 * funding figures rather than the last moderator's note. `CampaignDirectory` on the
 * service side carries the argument at length.
 */

/** What one request reads. The service clamps anything larger. */
export const DIRECTORY_PAGE_SIZE = 25;

/**
 * The states this screen offers as filters, in the order a campaign passes through them.
 *
 * <p>§6.1's whole enum, because the question this screen answers is "what is there" and
 * any state is a legitimate answer to it. A shorter list would be this module deciding
 * which parts of the platform staff are allowed to look at.
 */
export const DIRECTORY_STATES: readonly ProjectState[] = [
  'DRAFT',
  'PRELAUNCH',
  'SUBMITTED',
  'CHANGES_REQUESTED',
  'APPROVED',
  'SCHEDULED',
  'LIVE',
  'COLLECTING',
  'LATE_PLEDGE',
  'SUCCESSFUL',
  'UNSUCCESSFUL',
  'FULFILLING',
  'COMPLETED',
  'REJECTED',
  'SUSPENDED',
  'CANCELED',
];

/** One campaign, as the directory lists it. */
export interface DirectoryCampaign {
  /** Also this row's keyset position: `after` takes it to continue from here. */
  projectId: string;
  title: string;
  slug: string;
  state: ProjectState;
  /** When the creator started it. What this list is ordered by. */
  createdAt: string;
  /** Absent for everything that has never been live. */
  launchedAt?: string | null;
  /** Absent until it launches, and frozen from that moment. */
  deadline?: string | null;
  /** Absent on a draft that has not said what it needs yet. */
  goal?: { amount: string; currency: string } | null;
  /** Always present: a campaign that has raised nothing has raised zero. */
  pledged: { amount: string; currency: string };
  backersCount: number;
  creatorId: string;
  /**
   * Absent when the account has been anonymised since — §17.4 leaves the campaign
   * behind. The screen says so rather than rendering an empty name.
   */
  creatorName?: string | null;
  creatorSlug?: string | null;
}

export interface CampaignDirectoryPage {
  /** The filter this page was read with, or absent for every campaign. */
  state?: ProjectState | null;
  campaigns: readonly DirectoryCampaign[];
  /** Absent at the end of the list. A full page is the only signal there may be more. */
  nextCursor?: string | null;
}

/**
 * One page of the directory, newest first.
 *
 * <p>The filter reaches the service rather than narrowing a loaded page, which is the
 * rule the report queue states: twenty-five campaigns of which two are drafts is not a
 * page of two, and a client that dropped rows locally would hold a cursor that has
 * already moved past them.
 */
export async function listCampaigns(options: {
  state?: ProjectState | null;
  after?: string | null;
  limit?: number;
  signal?: AbortSignal;
}): Promise<CampaignDirectoryPage> {
  const query = new URLSearchParams();
  if (options.state != null) query.set('state', options.state);
  if (options.after != null && options.after !== '') query.set('after', options.after);
  query.set('limit', String(options.limit ?? DIRECTORY_PAGE_SIZE));

  const response = await authorizedFetch(`/v1/admin/projects?${query}`, { signal: options.signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as CampaignDirectoryPage;
}
