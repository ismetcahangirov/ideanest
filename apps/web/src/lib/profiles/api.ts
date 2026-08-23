import { authorizedFetch, publicFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { Page } from '../community/signals';
import type { Money } from '../money';

/**
 * §4.2's public profile — P-04, P-05, P-06 and P-07, issue #274.
 *
 * <h2>One module for four endpoints, because they are one page</h2>
 *
 * `GET /v1/users/{slug}`, its two lists, and the single write that decides whether any of
 * them answer at all. `lib/community/signals.ts` states the rule this follows — a screen's
 * data and its paginator belong together — and the alternative here is worse than usual: the
 * three reads share the 404 contract below, and a second module that read one of them would
 * be a second place for "private is indistinguishable from absent" to be got wrong.
 *
 * <h2>404 is the whole design, and the client must not decorate it</h2>
 *
 * The service answers **404** for an unknown slug, for a closed account, and for an account
 * whose `profile_visibility` is `PRIVATE`. Three different facts, one answer, deliberately:
 * a 403 would confirm that the person exists and has chosen to hide, which is precisely the
 * thing P-07 is for. So nothing in this module distinguishes them, nothing renders "this
 * profile is private", and the page renders the ordinary not-found route for all three.
 *
 * A client that tried to be helpful here — "that account is private" — would be an oracle
 * built on top of an endpoint designed not to be one, and it would leak the fact from the
 * browser rather than from the service, which is not an improvement.
 *
 * <h2>`publicFetch`, not `authorizedFetch`, for the reads</h2>
 *
 * All three are `permitAll` and a profile is what a signed-out visitor follows from a
 * campaign page. `authorizedFetch` throws when there is no token, which would turn "look at
 * who made this" into "sign in first". The one write is the caller's own account and takes
 * `authorizedFetch`.
 *
 * <h2>Money is on the created list and never on the backed one</h2>
 *
 * `ProfileProjectCard` carries `goal` and `pledged` because a creator's own campaigns are
 * public figures — the same numbers the discovery card shows. **P-04 says the backed archive
 * carries no amounts**, and the service omits them there rather than sending zeroes. Both
 * lists are the same type because they are the same rows from the same table; what differs
 * is what the service is willing to say about them, and `components/profile/ProfileCampaignCard`
 * is where that difference is enforced on screen. See its doc comment before "improving" it.
 */

/** §4.2 P-07. `PUBLIC` is the column default and what every account starts as. */
export type ProfileVisibility = 'PUBLIC' | 'PRIVATE';

/** A cover, as every projection in this application carries one. */
export interface CoverImage {
  readonly url: string;
  readonly width: number;
  readonly height: number;
}

/**
 * The person, as `GET /v1/users/{slug}` describes them.
 *
 * Five fields and no e-mail address, no identifier and no visibility. The account's own
 * identifier is absent because nothing public is addressed by it — the slug is the address —
 * and `profile_visibility` is absent because a profile that answers at all is public by
 * definition, so a field saying so would carry no information and a field saying otherwise
 * could not exist.
 *
 * <h2>THERE ARE NO COUNTS ON THIS PROJECTION, AND THAT IS A BOUNDARY RATHER THAN AN OVERSIGHT</h2>
 *
 * "How many campaigns has this person created" is a question about `project`, and "how many
 * have they backed" is a question about `pledge`. Answering either from the `user` module —
 * the module every other module depends on — would make it depend on both in turn, which
 * `ModuleBoundaryTests` refuses outright. So the response carries none, and a client that
 * wants a number counts the rows it is already rendering.
 *
 * **Which means a client may only print a total it can actually know.** The lists are
 * paginated, so the length of a loaded page is a total exactly when there is no next cursor
 * and is a fraction otherwise. `components/profile/ProfileTabs` prints one under precisely
 * that condition and prints nothing the rest of the time; a figure that silently means "the
 * first twenty-four" is worse than no figure, because a reader has no way to tell which they
 * are looking at.
 *
 * <h2>Nulls are written out, deliberately</h2>
 *
 * Unlike most projections in this service, this one serialises `bio` and `avatarUrl` as
 * `null` rather than omitting them, so that "this person wrote no biography" and "the field I
 * expected is missing" are different things on the wire. They are typed as `T | null` and not
 * `?: T | null` for that reason: a caller that treated an absent key as "still loading" would
 * put a spinner over an answer it had already received.
 */
export interface PublicProfile {
  readonly slug: string;
  readonly name: string;
  readonly avatarUrl: string | null;
  /** §4.2 P-02. Plain text. `null` is a person who has written none, not a missing field. */
  readonly bio: string | null;
  /** ISO-8601 instant, UTC. */
  readonly joinedAt: string;
}

/**
 * One campaign on a profile, in either list.
 *
 * Narrower than `lib/discovery/api.ts`'s card and deliberately not that type: the discovery
 * feed carries a badge, a `daysLeft` and a pre-computed `completionPercent` that the search
 * projection produces, and none of those exist here. Importing the feed's card would mean
 * either inventing those three fields on the client or rendering a card with three holes in
 * it.
 *
 * `goal` and `pledged` are `Money` — a string and a currency, never a JSON number
 * (CLAUDE.md §3). **Both are absent on the backed list.** They are optional here rather than
 * split into two types because the wire shape is one shape; see the module comment.
 */
export interface ProfileProjectCard {
  readonly id: string;
  readonly title: string;
  readonly slug: string;
  readonly creatorSlug: string;
  readonly blurb: string | null;
  /** One of §6.1's nine publicly visible states. Widened, so an unknown one renders. */
  readonly state: string;
  readonly goal?: Money | null;
  readonly pledged?: Money | null;
  readonly backersCount: number;
  /** ISO-8601 instants, UTC. Null before a campaign has launched or has a deadline. */
  readonly deadline: string | null;
  readonly launchedAt: string | null;
  readonly coverImage: CoverImage | null;
}

/**
 * How many rows a page asks for.
 *
 * The same twenty-four `lib/community/signals.ts` uses, and for the same reason: it divides
 * by two, three and four, so the last row of the grid is full at every breakpoint this
 * application lays out.
 */
export const PROFILE_PAGE_SIZE = 24;

/** The wire shape of both lists. `projects`, not `items` — see `pageOf`. */
interface RawProjectPage {
  readonly projects?: readonly ProfileProjectCard[];
  readonly nextCursor?: string | null;
}

/**
 * A list response as `useCursorList` reads it.
 *
 * The service names the array after what is in it and the hook names it `items`, and this is
 * the one line that reconciles them. Renaming the field on the service would be the tidier
 * fix and is not this client's to make; renaming the hook's would be a change to two screens
 * that already ship.
 */
function pageOf(body: RawProjectPage): Page<ProfileProjectCard> {
  return { items: body.projects ?? [], nextCursor: body.nextCursor ?? null };
}

async function readProjectPage(
  path: string,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<Page<ProfileProjectCard>> {
  const query = new URLSearchParams({ limit: String(PROFILE_PAGE_SIZE) });
  if (cursor !== null) query.set('cursor', cursor);

  const response = await publicFetch(`${path}?${query.toString()}`, { signal });
  if (!response.ok) throw await errorFrom(response);

  return pageOf((await response.json()) as RawProjectPage);
}

/** The public address of a profile. §10.2 addresses a person by slug and so does this. */
export function profileHref(slug: string): string {
  return `/u/${encodeURIComponent(slug)}`;
}

/** The public address of a campaign, from a card on either list. */
export function profileCampaignHref(card: ProfileProjectCard): string {
  return `/projects/${encodeURIComponent(card.creatorSlug)}/${encodeURIComponent(card.slug)}`;
}

/**
 * §4.2 P-05 — one page of the campaigns this person created.
 *
 * Only the nine publicly visible states of §6.1. A creator's drafts, submissions and
 * rejections are not on it, which is also why a count taken from this list is the only honest
 * one: a total computed anywhere else would be counting rows this list does not contain.
 */
export function listCreatedProjects(
  slug: string,
  cursor: string | null = null,
  signal?: AbortSignal,
): Promise<Page<ProfileProjectCard>> {
  return readProjectPage(`/v1/users/${encodeURIComponent(slug)}/projects`, cursor, signal);
}

/**
 * §4.2 P-04 — one page of the campaigns this person backed, **with no amounts**.
 *
 * Two omissions, both the service's and neither reconstructible here:
 *
 *   - **no amounts of any kind.** Not what they pledged, and not what the campaign raised.
 *     P-04 is explicit, and the reason is that a public archive of what somebody spent is a
 *     financial profile of them that they did not publish.
 *   - **anonymous pledges are not on it** (§4.5 PL-12). A pledge made anonymously is hidden
 *     from public lists, and this is one.
 *
 * Both are enforced on the server, which is the only place they can be enforced: a client
 * that filtered would be a client that had already received the thing it was hiding.
 */
export function listBackedProjects(
  slug: string,
  cursor: string | null = null,
  signal?: AbortSignal,
): Promise<Page<ProfileProjectCard>> {
  return readProjectPage(`/v1/users/${encodeURIComponent(slug)}/backed`, cursor, signal);
}

/**
 * §4.2 P-07 — `PATCH /v1/me/profile-visibility`.
 *
 * 204 and no body. The account is the caller's, so there is no identifier in the path: an
 * endpoint that took one would be an endpoint somebody would eventually try to point at
 * somebody else.
 */
export async function setProfileVisibility(visibility: ProfileVisibility): Promise<void> {
  const response = await authorizedFetch('/v1/me/profile-visibility', {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ visibility }),
  });

  if (!response.ok) throw await errorFrom(response);
}

/**
 * What visibility the account's own profile is currently set to — **read from its effect**.
 *
 * <h2>Why this exists at all</h2>
 *
 * P-07's control is a write and there is no matching read: `GET /v1/me` carries six fields
 * and `profileVisibility` is not one of them. A switch that cannot show its own position is
 * not a switch, and the alternatives were both bad — guess `PUBLIC` because that is the
 * column default, which is a lie to everybody who has already turned it off; or offer two
 * buttons and no state, which asks somebody to make a decision the interface will not tell
 * them they have already made.
 *
 * So the setting is read from the thing it decides. `GET /v1/users/{slug}` answers 200 for a
 * public profile and 404 for a private one, and for the caller's own slug — an account that
 * is signed in, and therefore neither unknown nor closed — those are the only two answers
 * the contract allows. That makes the probe exact rather than a heuristic.
 *
 * <h2>It is deliberately anonymous, and that is the point rather than an economy</h2>
 *
 * A plain `fetch` rather than `publicFetch`, because `publicFetch` attaches the access token
 * when there is one and the question being asked is *what does a stranger see*. If the
 * service ever chose to show an owner their own hidden profile, a request carrying the
 * owner's token would answer 200 for a private account and the switch would read the
 * opposite of the truth. `credentials: 'omit'` makes the request unable to be recognised,
 * which is the only way to be sure the answer is the visitor's.
 *
 * `null` means neither — the service was unreachable, or answered something the contract
 * does not describe. The panel renders that as "not known" and refuses to move a switch it
 * cannot position, rather than picking a default and writing over somebody's choice.
 */
export async function probeProfileVisibility(
  slug: string,
  signal?: AbortSignal,
): Promise<ProfileVisibility | null> {
  const response = await fetch(`/v1/users/${encodeURIComponent(slug)}`, {
    credentials: 'omit',
    headers: { accept: 'application/json' },
    // Never a stored copy: the answer is what the previous write just changed.
    cache: 'no-store',
    ...(signal === undefined ? {} : { signal }),
  });

  if (response.ok) return 'PUBLIC';
  if (response.status === 404) return 'PRIVATE';
  return null;
}
