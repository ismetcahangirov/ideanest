import type { Page } from '../community/signals';
import type { EnvSource } from '../seo/metadata';
import { apiOrigin } from '../seo/metadata-source';
import { PROFILE_PAGE_SIZE, type ProfileProjectCard, type PublicProfile } from './api';

/**
 * The profile page's server reads — §4.2 P-04 to P-06, issue #274.
 *
 * <h2>Why the page is rendered on the server at all</h2>
 *
 * A profile is a public page with a permanent address that a search engine, a link unfurler
 * and a reader on a slow connection all ask for. #119 made the same argument for the campaign
 * page and the reasoning is unchanged — but here there is a second, sharper reason:
 * **the 404 has to be a real 404.**
 *
 * An unknown slug, a closed account and a private profile are one answer (`lib/profiles/api.ts`
 * explains why), and that answer is only worth anything if it arrives with a 404 status. A
 * client-rendered page has already sent 200 and its headers by the time it learns the profile
 * is not there, so the best it could do is paint not-found text under a successful response —
 * a soft 404, which a crawler indexes and keeps asking for. `notFound()` from a Server
 * Component sends the status, which is the only version of this that means anything.
 *
 * <h2>Why this is not `lib/api/server.ts`</h2>
 *
 * That module reads through `@ideanest/api-client`, whose types are generated from
 * `apps/api/openapi.json` — a file this pull request's coordinator owns and regenerates after
 * every endpoint lands. Reaching for it would make this page's types depend on the order two
 * agents merged in. These three endpoints are read with a plain `fetch` against the same
 * origin the generated client uses, exactly as `lib/seo/metadata-source.ts` already does for
 * the pre-launch preview, and the shapes are stated once in `./api.ts`.
 *
 * <h2>Anonymous, always</h2>
 *
 * `credentials: 'omit'` and no `Authorization` header. `metadata-source.ts` makes the
 * argument for a social card and it is stronger here: the whole page is a statement about
 * what a stranger may see, and a server render that varied by session would be a page no
 * shared cache could hold and — worse — a page whose 404 depended on who asked.
 *
 * <h2>Failure is `null`, and the caller decides what that means</h2>
 *
 * The same rule `lib/api/server.ts` states. For the profile, `null` is `notFound()`. For a
 * list, `null` is a panel that says the list could not be loaded — which is a different thing
 * from an empty list, and a page that could not tell them apart would print "no campaigns
 * yet" over a service that was restarting.
 */

export interface ProfileReadOptions {
  /** Injected in tests. Defaults to the platform `fetch`. */
  readonly fetchImpl?: typeof fetch;
  readonly env?: EnvSource;
  readonly signal?: AbortSignal;
}

/**
 * A minute, matching every other public read in this application.
 *
 * A profile's own fields move perhaps twice a year, but the two lists on it move whenever one
 * of its campaigns launches or somebody backs one — and the three are read together for one
 * page. Giving the profile a longer window than its lists would produce a heading that says
 * "12 campaigns" above a list of thirteen.
 */
const PROFILE_REVALIDATE_SECONDS = 60;

async function read<T>(path: string, options: ProfileReadOptions): Promise<T | null> {
  const fetchImpl = options.fetchImpl ?? fetch;

  try {
    const response = await fetchImpl(`${apiOrigin(options.env)}${path}`, {
      credentials: 'omit',
      headers: { accept: 'application/json' },
      next: { revalidate: PROFILE_REVALIDATE_SECONDS },
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });

    if (!response.ok) return null;
    return (await response.json()) as T;
  } catch {
    /*
     * Every failure is the same answer here, unlike `lib/api/server.ts`, which rethrows what
     * is not a refusal. That module can afford to: it reads through a typed client that
     * distinguishes a refusal from a `TypeError`. This one has a bare `fetch`, so a thrown
     * value is a network failure, an aborted request, or a body that was not the JSON it
     * claimed to be — and none of those is something a public page should turn into a 500.
     */
    return null;
  }
}

/**
 * The person at `/u/{slug}` — `GET /v1/users/{slug}`.
 *
 * `null` for an unknown slug, a closed account, and a private profile, indistinguishably. See
 * `lib/profiles/api.ts`: the service answers 404 for all three so that the client cannot be
 * used to tell them apart, and this function does not undo that by inspecting the status.
 */
export function fetchPublicProfile(
  slug: string,
  options: ProfileReadOptions = {},
): Promise<PublicProfile | null> {
  return read<PublicProfile>(`/v1/users/${encodeURIComponent(slug)}`, options);
}

interface RawProjectPage {
  readonly projects?: readonly ProfileProjectCard[];
  readonly nextCursor?: string | null;
}

async function readFirstPage(
  path: string,
  options: ProfileReadOptions,
): Promise<Page<ProfileProjectCard> | null> {
  const body = await read<RawProjectPage>(`${path}?limit=${PROFILE_PAGE_SIZE}`, options);
  if (body === null) return null;

  return { items: body.projects ?? [], nextCursor: body.nextCursor ?? null };
}

/**
 * The first page of §4.2 P-05's created campaigns.
 *
 * **The first page only.** Everything after it is fetched in the browser by the tab panel,
 * because a "show more" that re-rendered the route on the server would be a full navigation
 * for a list that grows downward. The first page is the part that has to be in the HTML.
 */
export function fetchCreatedProjects(
  slug: string,
  options: ProfileReadOptions = {},
): Promise<Page<ProfileProjectCard> | null> {
  return readFirstPage(`/v1/users/${encodeURIComponent(slug)}/projects`, options);
}

/**
 * The first page of §4.2 P-04's backed archive — **with no amounts**, which is the service's
 * doing and not something this function strips.
 *
 * It is fetched here rather than when the tab is opened, and that is a decision worth naming.
 * A tab that fetches on activation is one request saved for every visitor who never opens it,
 * and it costs a loading state inside a widget somebody has just pressed a key to reach. The
 * two panels are one document, one page of each, and both are readable with JavaScript
 * disabled and by a crawler that never fires a click.
 */
export function fetchBackedProjects(
  slug: string,
  options: ProfileReadOptions = {},
): Promise<Page<ProfileProjectCard> | null> {
  return readFirstPage(`/v1/users/${encodeURIComponent(slug)}/backed`, options);
}
