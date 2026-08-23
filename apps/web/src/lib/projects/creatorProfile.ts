import type { EnvSource } from '../seo/metadata';
import { apiOrigin } from '../seo/metadata-source';
import type { Money } from '../money';
import type { ProjectState } from './api';

/**
 * §4.4's Creator tab, read from §4.2's public profile — issue #282.
 *
 * <h2>Why the campaign response is not enough, and what is not invented to cover it</h2>
 *
 * `GET /v1/projects/{creatorSlug}/{projectSlug}` carries `creator` as `{slug, name,
 * avatarUrl}` and nothing else. §4.4's table asks the Creator tab for "biography, history,
 * previous projects, contact", and three of those four are simply not on that response.
 *
 * They are not fabricated here. A tab that printed "Member since —" or an empty biography
 * box would be making a statement about the creator out of the absence of a field, and on a
 * page whose subject is whether to send that person money, an invented blank is worse than a
 * missing row. <strong>Every row on this tab is omitted when the field behind it is
 * absent</strong>, and the two rows the platform has no field for at all are named:
 *
 * <ul>
 *   <li><strong>Contact.</strong> §4.9's C-12 — direct messages between a creator and a
 *       backer — is half built: a creator can message their backers, and the reply half does
 *       not exist. There is no address, no form, and no endpoint that would carry a message
 *       from this page to this creator, so the tab offers none. Reporting the campaign is at
 *       the foot of the page and is a different thing.
 *   <li><strong>History as a figure.</strong> There is no campaign count and no backed
 *       count on the profile, and the reason is structural rather than an omission: counting
 *       either would give the `user` module a dependency on `project` and `pledge`, which the
 *       module-boundary test refuses. So the Creator tab states history the only way it
 *       honestly can — by listing the campaigns `GET /v1/users/{slug}/projects` returns. It
 *       must not print a total, because a list capped at {@link CREATOR_PROJECT_LIMIT} is not
 *       a count and presenting it as one would understate a prolific creator's record.
 *   <li><strong>A funding track record.</strong> Even with the list, "how many of their
 *       campaigns funded" is a conclusion this page would be drawing rather than a fact the
 *       service published. The cards carry their own state and say it themselves.
 * </ul>
 *
 * <h2>The two endpoints, and why they are read with a bare `fetch`</h2>
 *
 * `GET /v1/users/{slug}` and `GET /v1/users/{slug}/projects` land in this same pull request,
 * from the service side. They are therefore <em>not</em> in `packages/api-client`'s generated
 * `schema.ts` yet — that file is regenerated from `apps/api/openapi.json` by whoever
 * assembles the branch — so the typed client cannot name these paths and would fail the
 * typecheck if it tried.
 *
 * This is the one honest way through, and it is deliberately the smallest one: the same
 * origin resolution (`apiOrigin`), the same anonymous read, the same revalidation window and
 * the same `null`-on-refusal rule as every other server read in the application, expressed
 * without the generated types. <strong>It is not a new fetch layer</strong> and must not
 * become one — when the schema is regenerated, the two calls below become
 * `client.get('/v1/users/{slug}')` and the readers underneath them do not change.
 *
 * <h2>404 is the whole of the error handling, and that is by design</h2>
 *
 * The profile endpoint answers 404 — never 403 — for an unknown slug, a deleted account and
 * an account whose `profile_visibility` is `PRIVATE`. That is an oracle-free refusal: a 403
 * would confirm that a slug belongs to somebody who has chosen to be private, which is
 * exactly what choosing to be private is meant to prevent. So this module treats every
 * refusal identically and answers `null`, and the tab renders what the campaign response
 * already carries — the creator's name and avatar — with no profile link and no explanation
 * of why there is none. A sentence saying "this creator's profile is private" would rebuild
 * the oracle in the interface.
 */

/**
 * §4.2's public profile — `GET /v1/users/{slug}`.
 *
 * Five fields, which is all the endpoint publishes. `bio` and `avatarUrl` arrive as an
 * explicit `null` rather than being absent when the creator has set neither, so `null` here
 * means "they have not written one" and never "it has not arrived yet" — the tab renders the
 * empty state for it rather than a placeholder.
 */
export interface PublicProfile {
  readonly slug: string;
  readonly name: string;
  readonly avatarUrl: string | null;
  /** The creator's own words. `null` when they have written none. */
  readonly bio: string | null;
  /** ISO-8601 instant, UTC. `null` when the service did not send one. */
  readonly joinedAt: string | null;
}

/** One campaign on a creator's list — `ProfileProjectCard`. */
export interface CreatorProject {
  readonly id: string;
  readonly title: string;
  readonly slug: string;
  readonly creatorSlug: string;
  readonly blurb: string | null;
  readonly state: ProjectState;
  readonly goal: Money | null;
  readonly pledged: Money | null;
  readonly backersCount: number;
  readonly deadline: string | null;
  readonly launchedAt: string | null;
  readonly coverImage: { readonly url: string; readonly width: number; readonly height: number } | null;
}

export interface CreatorProjectPage {
  readonly projects: readonly CreatorProject[];
  readonly nextCursor: string | null;
}

/**
 * How many of a creator's other campaigns the tab shows.
 *
 * Six, not a page. This is a tab on somebody else's campaign, and its job is to answer "has
 * this person done this before" — not to be the creator's profile, which is a route of its
 * own that this tab links to. A reader who wants the rest follows the link.
 */
export const CREATOR_PROJECT_LIMIT = 6;

/** A minute, matching `lib/api/server.ts` and the service's own `Cache-Control`. */
const PUBLIC_READ_REVALIDATE_SECONDS = 60;

export interface CreatorReadOptions {
  /** Injected in tests. Defaults to the platform `fetch`. */
  readonly fetchImpl?: typeof fetch;
  readonly env?: EnvSource;
  readonly revalidateSeconds?: number;
}

/**
 * The public address of a creator's profile.
 *
 * One function, so the Creator tab, the byline and anything else that grows a link cannot
 * come to two spellings of it. The route itself belongs to #274 and is not created here.
 */
export function profileHref(slug: string): string {
  return `/u/${encodeURIComponent(slug)}`;
}

async function readJson(
  path: string,
  options: CreatorReadOptions,
): Promise<unknown | null> {
  const impl = options.fetchImpl ?? fetch;

  try {
    const response = await impl(`${apiOrigin(options.env)}${path}`, {
      headers: { accept: 'application/json' },
      next: { revalidate: options.revalidateSeconds ?? PUBLIC_READ_REVALIDATE_SECONDS },
      /*
       * `RequestInit` has no `next` in the DOM library — it is Next's extension of it — so
       * the object is widened here rather than the whole module being typed against a
       * framework-specific global. The cast is over the shape of an options bag, not over a
       * response body, which is the distinction that keeps `no any` meaningful.
       */
    } as RequestInit);

    // Every refusal is one answer. See the module comment on why 404 is the only one the
    // profile endpoints make, and why this must not distinguish them.
    if (!response.ok) return null;
    return (await response.json()) as unknown;
  } catch {
    /*
     * A service that cannot be reached is the case a public page must survive, and `fetch`
     * reports it as a bare `TypeError` with no status to inspect. The Creator tab degrades
     * to the campaign's own `{slug, name, avatarUrl}`, which is still a truthful byline.
     */
    return null;
  }
}

/** The creator's profile, or `null` for every refusal alike. */
export async function fetchPublicProfile(
  slug: string,
  options: CreatorReadOptions = {},
): Promise<PublicProfile | null> {
  return readProfile(await readJson(`/v1/users/${encodeURIComponent(slug)}`, options));
}

/**
 * The creator's public campaigns, newest first, or `null` for every refusal alike.
 *
 * The service already restricts this to §6.1's nine public states, so nothing below filters
 * by state. The campaign currently being read is dropped by the component rather than here:
 * this function answers "what has this person made", and which of those the reader is
 * already looking at is the caller's question.
 */
export async function fetchCreatorProjects(
  slug: string,
  options: CreatorReadOptions = {},
): Promise<CreatorProjectPage | null> {
  const body = await readJson(
    `/v1/users/${encodeURIComponent(slug)}/projects?limit=${CREATOR_PROJECT_LIMIT + 1}`,
    options,
  );
  return body === null ? null : readProjectPage(body);
}

/* -------------------------------------------------------------------------
 * Reading one body
 *
 * The same narrowing `lib/projects/publicPage.ts` argues for at length: the wire shapes are
 * optional field by field, and a component that narrowed at every use would eventually skip
 * a check and print `undefined` into somebody's profile. It happens once, here.
 * ---------------------------------------------------------------------- */

/** Exported for the test, which is the only way to state the omission rules without a network. */
export function readProfile(value: unknown): PublicProfile | null {
  if (value === null || typeof value !== 'object') return null;

  const source = value as Record<string, unknown>;
  const slug = text(source['slug']);
  const name = text(source['name']);
  // A profile with no handle or no name is not a weaker profile; it is a body this tab
  // cannot address or introduce, and the campaign's own creator fields are the better answer.
  if (slug === null || name === null) return null;

  return {
    slug,
    name,
    avatarUrl: text(source['avatarUrl']),
    bio: text(source['bio']),
    joinedAt: text(source['joinedAt']),
  };
}

/** Exported for the test. */
export function readProjectPage(value: unknown): CreatorProjectPage {
  if (value === null || typeof value !== 'object') return { projects: [], nextCursor: null };

  const source = value as Record<string, unknown>;
  const rows = source['projects'];

  const projects: CreatorProject[] = [];
  if (Array.isArray(rows)) {
    for (const row of rows as readonly unknown[]) {
      const project = readProject(row);
      if (project !== null) projects.push(project);
    }
  }

  return { projects, nextCursor: text(source['nextCursor']) };
}

function readProject(value: unknown): CreatorProject | null {
  if (value === null || typeof value !== 'object') return null;

  const source = value as Record<string, unknown>;
  const id = text(source['id']);
  const title = text(source['title']);
  const slug = text(source['slug']);
  const creatorSlug = text(source['creatorSlug']);
  const state = text(source['state']);
  // Without all five there is no link to make and nothing to call it, which is a row that
  // would render as an unlabelled hole in a list of somebody's work.
  if (id === null || title === null || slug === null || creatorSlug === null || state === null) {
    return null;
  }

  return {
    id,
    title,
    slug,
    creatorSlug,
    blurb: text(source['blurb']),
    state: state as ProjectState,
    goal: money(source['goal']),
    pledged: money(source['pledged']),
    backersCount: count(source['backersCount']) ?? 0,
    deadline: text(source['deadline']),
    launchedAt: text(source['launchedAt']),
    coverImage: readCoverImage(source['coverImage']),
  };
}

function readCoverImage(value: unknown): CreatorProject['coverImage'] {
  if (value === null || typeof value !== 'object') return null;

  const source = value as Record<string, unknown>;
  const url = text(source['url']);
  const { width, height } = source;
  // The three columns are written together or not at all, and the dimensions are what let a
  // card reserve its box before the photograph decodes.
  if (url === null || typeof width !== 'number' || typeof height !== 'number') return null;
  if (width <= 0 || height <= 0) return null;

  return { url, width, height };
}

/**
 * `{"amount": "599.00", "currency": "AZN"}`, or null.
 *
 * The amount stays the string it arrived as. CLAUDE.md §3: money never becomes a number on
 * the way in, because a number is where the precision goes.
 */
function money(value: unknown): Money | null {
  if (value === null || typeof value !== 'object') return null;

  const source = value as Record<string, unknown>;
  const amount = source['amount'];
  const currency = source['currency'];
  if (typeof amount !== 'string' || typeof currency !== 'string') return null;

  return { amount, currency };
}

/**
 * A non-negative whole number, or `null`.
 *
 * `null` rather than zero for an absent count. "0 campaigns" and "the service did not say"
 * are different statements, and the tab omits the row for the second rather than printing
 * the first.
 */
function count(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null;
  return Math.floor(value);
}

function text(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}
