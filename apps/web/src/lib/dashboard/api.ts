import type { components } from '@ideanest/api-client';
import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { ProjectState } from '../projects/api';

/**
 * What the creator dashboard asks the service — §4.7's CD-01, and #93.
 *
 * <h2>Why this is a browser read and not a server render</h2>
 *
 * `lib/api/server.ts` exists for #119's public pages and is explicitly anonymous: it
 * sends no token, because a server render that varied by session is a page nothing can
 * cache and the crawler is the reader it exists for. None of that applies here. The
 * dashboard is one creator's view of their own money, behind a bearer token, and the
 * service answers it `no-store`.
 *
 * So it is fetched after hydration, like the moderation queue. What that costs is a
 * loading state, and the loading state is honest: the numbers on this screen are the ones
 * that move while you are looking at them.
 *
 * <h2>One function, deliberately</h2>
 *
 * The other four routes §10.2 lists under Dashboard — `/analytics`, `/referrers`,
 * `/backers`, `/finance` — are #96, #94, #97 and #99. Each gets its own reader when it
 * gets its own panel. Collecting them here in advance would be a module shaped by a
 * screen that does not exist yet.
 */

/** The dashboard projection, as the published contract describes it. */
export type DashboardResponse = components['schemas']['DashboardResponse'];

/**
 * The same body with `state` narrowed to §6.1's sixteen.
 *
 * The contract types it `string`, because the service sends the enum constant by name and
 * springdoc has no enum to publish for a `String` field. `lib/projects/api.ts` already
 * declares the union every other screen branches on, and reusing it is what keeps a
 * missed state a type error here rather than a card that renders nothing.
 */
export type CampaignDashboard = Omit<DashboardResponse, 'state'> & { readonly state: ProjectState };

/**
 * The campaign's live totals.
 *
 * @throws ApiError on any refusal. The caller decides what each status means — a 404 is
 *   "not yours or not there" and a 403 is "your grant does not include the finances", and
 *   only the screen knows how to say either.
 */
export async function getDashboard(projectId: string): Promise<CampaignDashboard> {
  const response = await authorizedFetch(`/v1/projects/${encodeURIComponent(projectId)}/dashboard`, {
    // Matching the service's own `no-store`. A dashboard served from the browser's cache
    // is a set of figures that stopped moving, and `serverTime` in the body would then be
    // a stale instant the countdown calibrates itself against.
    cache: 'no-store',
  });

  if (!response.ok) {
    throw await errorFrom(response);
  }
  return (await response.json()) as CampaignDashboard;
}
