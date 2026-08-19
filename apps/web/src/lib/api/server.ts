import { ApiError, createApiClient, type ApiClient, type components } from '@ideanest/api-client';
import type { DiscoveryFeed } from '../discovery/api';
import type { EnvSource } from '../seo/metadata';
import { apiOrigin } from '../seo/metadata-source';

/**
 * Reads the service makes on the server, for the pages #119 renders there.
 *
 * <h2>Why this is not `lib/api/client.ts`</h2>
 *
 * That module talks to `/v1` as a RELATIVE path, and that is deliberate: in a browser the
 * request is same-origin because `next.config.mjs` rewrites `/v1` to the service, which is
 * the only arrangement in which a `SameSite=Strict` refresh cookie travels at all.
 *
 * A relative path has no meaning inside a Server Component. There is no document, no
 * origin, and no rewrite — the rewrite is a property of the HTTP server this code runs
 * *in*, not of `fetch` — so the call that works in the browser throws `Failed to parse
 * URL` on the server. Server reads therefore go straight to `IDEANEST_API_ORIGIN`, which
 * `apiOrigin` reads from the same variable the rewrite does.
 *
 * <h2>Anonymous, and that is a decision rather than a limitation</h2>
 *
 * Nothing here sends a token. The pages that use it are public — a campaign page and the
 * discovery feed — and a server render that varied by session would be a page that cannot
 * be cached by anything, shared by anybody, or served to the crawler #119 exists for. A
 * signed-in visitor's personal additions to those pages (whether they have saved a
 * campaign, whether they have backed it) belong to a client component that fetches them
 * after hydration, because those are the parts that must not be in a shared cache.
 *
 * <h2>Failure is `null`, never a thrown page</h2>
 *
 * Every reader below answers `null` when the service refuses or cannot be reached. A
 * Server Component that throws takes the whole route to an error page, and the difference
 * between "this campaign does not exist" and "the API is restarting" is the difference
 * between a 404 and a 500 — decided at the call site, where the page knows which it wants.
 */

/** The campaign page's projection, as the published contract describes it. */
export type ProjectPageResponse = components['schemas']['ProjectPageResponse'];

/** The public reward list, likewise. */
export type PublicRewardListResponse = components['schemas']['PublicRewardListResponse'];

export interface ServerReadOptions {
  /** Injected in tests. Defaults to the platform `fetch`. */
  readonly fetchImpl?: typeof fetch;
  readonly env?: EnvSource;
  /** §10.3's localisation header. Decides the taxonomy names a campaign page shows. */
  readonly locale?: string;
  /** Overrides the shared window below. Per call, because a caller may know better. */
  readonly revalidateSeconds?: number;
}

/**
 * A minute, matching the `Cache-Control` the service puts on these reads.
 *
 * The service already decided how stale its own public reads may be — a campaign's pledged
 * total and the feed's ordering both move continuously, and sixty seconds is what
 * `PublicProjectController` and `DiscoveryController` chose. Holding a different opinion
 * here would mean two caches disagreeing about the same bytes, with the tighter one winning
 * by accident.
 */
const PUBLIC_READ_REVALIDATE_SECONDS = 60;

/**
 * A client bound to the service's own origin.
 *
 * Built per call rather than held in a module constant: `apiOrigin` reads `process.env`,
 * and a constant would capture the value at module evaluation — which during a Next build
 * is before the deployment's environment exists.
 */
function client(options: ServerReadOptions): ApiClient {
  const baseUrl = apiOrigin(options.env);
  return options.fetchImpl === undefined
    ? createApiClient({ baseUrl })
    : createApiClient({ baseUrl, fetch: options.fetchImpl });
}

function readOptions(options: ServerReadOptions) {
  return {
    ...(options.locale === undefined ? {} : { headers: { 'accept-language': options.locale } }),
    next: { revalidate: options.revalidateSeconds ?? PUBLIC_READ_REVALIDATE_SECONDS },
  };
}

/**
 * The campaign at a public URL — §10.2's `GET /v1/projects/{creatorSlug}/{projectSlug}`.
 *
 * `null` for a campaign that does not exist and for one whose state is not public. The
 * service answers both with 404 for the reason `PublicProjects` gives, and they stay
 * indistinguishable here: a page that rendered "this campaign is private" would be the
 * oracle the 404 exists to prevent.
 */
export async function fetchCampaignPage(
  creatorSlug: string,
  projectSlug: string,
  options: ServerReadOptions = {},
): Promise<ProjectPageResponse | null> {
  try {
    return await client(options).get('/v1/projects/{creatorSlug}/{projectSlug}', {
      path: { creatorSlug, projectSlug },
      ...readOptions(options),
    });
  } catch (cause) {
    return refusalOrRethrow(cause);
  }
}

/**
 * A campaign's public reward tiers — `GET /v1/projects/{id}/rewards/public`.
 *
 * A second request rather than a field on the projection, because the service keeps them
 * apart and says why: one response whose cost is decided by the longest tier list on the
 * platform, cached for as long as its least cacheable part, is worse than two.
 *
 * `null` rather than an empty list on failure. An empty list is a campaign offering nothing,
 * which is a real and different thing, and a page that could not tell them apart would print
 * "no rewards" over a service that was merely restarting.
 */
export async function fetchPublicRewards(
  projectId: string,
  options: ServerReadOptions = {},
): Promise<PublicRewardListResponse | null> {
  try {
    return await client(options).get('/v1/projects/{projectId}/rewards/public', {
      path: { projectId },
      ...readOptions(options),
    });
  } catch (cause) {
    return refusalOrRethrow(cause);
  }
}

/**
 * The first page of the discovery feed — `GET /v1/discover`.
 *
 * <strong>Takes the query string the address bar already holds.</strong> `feedQuery` in
 * `lib/discovery/api.ts` is the one place that turns filters into the service's parameter
 * names, and it is what the browser sends; a second serialisation here would be a second
 * chance to spell `showOnly` differently, and the symptom would be a server render and a
 * client refetch that disagree about which campaigns match.
 *
 * The string is split back into a parameter object for the typed client, so a repeated
 * filter stays repeated — `?tag=a&tag=b` is two tags and `?tag=a,b` is one tag whose name
 * contains a comma, which is what `DiscoveryQueryBinder` reads.
 */
export async function fetchDiscoveryFeed(
  query: string,
  options: ServerReadOptions = {},
): Promise<DiscoveryFeed | null> {
  try {
    const feed = await client(options).get('/v1/discover', {
      query: parametersOf(query),
      ...readOptions(options),
    });
    /*
     * Cast to the module that owns the discovery wire types. The generated schema describes
     * the same JSON, and every field of it as optional — springdoc marks a record component
     * required only when it can prove it — so a page reading `card.title` through the
     * generated type would be narrowing seventeen fields that are never absent.
     * `lib/discovery/api.ts` states the shape the client half already relies on, and one
     * statement of it is the point of that module.
     */
    return feed as unknown as DiscoveryFeed;
  } catch (cause) {
    return refusalOrRethrow(cause);
  }
}

/**
 * A query string as the typed client's parameter object.
 *
 * A repeated name becomes an array and a single name stays a string, because that is the
 * distinction the service draws and the one the client re-encodes.
 */
function parametersOf(query: string): Record<string, string | string[]> {
  const params = new URLSearchParams(query);
  const parameters: Record<string, string | string[]> = {};

  for (const name of new Set(params.keys())) {
    const values = params.getAll(name);
    parameters[name] = values.length === 1 ? (values[0] as string) : values;
  }
  return parameters;
}

/**
 * A refusal is `null`; anything else is a bug and is allowed to surface.
 *
 * The distinction matters. A 404 or a 429 is the service answering, and a page can render
 * something sensible for it. A `TypeError` from a malformed base URL is not an answer, and
 * swallowing it would turn a misconfigured deployment into a site where every campaign has
 * quietly stopped existing — which is exactly the failure nobody notices in a staging
 * environment and everybody notices in production.
 *
 * A network-level failure is the one non-`ApiError` that must also be `null`: the service
 * being unreachable is precisely the case a public page has to survive, and `fetch` reports
 * it as a bare `TypeError` with no status to inspect. That is unsatisfying, and it is still
 * narrower than catching everything.
 */
function refusalOrRethrow(cause: unknown): null {
  if (cause instanceof ApiError || cause instanceof TypeError) return null;
  throw cause;
}
