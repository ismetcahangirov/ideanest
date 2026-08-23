import { ApiError, createApiClient } from '@ideanest/api-client';
import type { ServerReadOptions } from '../api/server';
import { apiOrigin } from '../seo/metadata-source';

/**
 * §4.4's Updates tab and §4.9's half of #83 — the numbered announcements on a campaign.
 *
 * <h2>The read is on the server, and that is the whole point of the tab</h2>
 *
 * `GET /v1/projects/{projectId}/updates` is `permitAll`, so there is nothing about it that
 * needs a browser. Fetching it after hydration would put the campaign's own announcements
 * behind JavaScript on the one route #119 exists to keep in the initial HTML — a creator's
 * "the moulds were late" is exactly the kind of thing somebody searches for by quoting it,
 * and a crawler served an empty list has been told the campaign has said nothing.
 *
 * <h2>The client does not filter, and could not</h2>
 *
 * §4.9 is explicit: `visibility` is stored and enforced, and the public read withholds a
 * `BACKERS_ONLY` update from anybody outside the campaign's team. What arrives here is
 * therefore already the list this caller may see. <strong>Nothing below drops a row</strong>,
 * and a client-side filter would be worse than redundant — it would be a second, weaker copy
 * of an entitlement rule, and the first time the two disagreed the weaker one would be the
 * one rendering a private update into public HTML.
 *
 * `visibility` is still read and still shown, because a public update marked as such is a
 * different statement from one that happens to be visible: a backer who can see a
 * backers-only update is entitled to know that not everybody can.
 *
 * <h2>Why the read is here rather than in `lib/api/server.ts`</h2>
 *
 * That module is where every other server-side public read lives and this one follows its
 * conventions exactly — anonymous, `next.revalidate` matching the service's own
 * `Cache-Control`, and `null` rather than a thrown page when the service refuses. It is not
 * <em>in</em> that file because it is shared ground in this branch: several agents are adding
 * public reads at once, and several sets of hands in one module is how two of them end up
 * with two spellings of the same helper. Folding these two functions back into it is a
 * tidy-up with no behaviour in it.
 *
 * <h2>Anonymous, and what that costs honestly</h2>
 *
 * The server read sends no token, for the reason `lib/api/server.ts` gives at length: a
 * server render that varied by session cannot be cached, shared, or served to a crawler. So
 * a signed-in member of the campaign's team is served the same list as a stranger. Today
 * that costs nothing at all, because §4.9 says the service withholds `BACKERS_ONLY` from
 * <em>everybody</em> outside the team and the team reads its own updates from the dashboard.
 * The day the service learns "has this account an active pledge", this tab needs a
 * client-side top-up for the signed-in case rather than a session-varying server render.
 */

/** The two values `project_updates.visibility` holds. Anything else is treated as unknown. */
export type UpdateVisibility = 'PUBLIC' | 'BACKERS_ONLY';

export interface CampaignUpdate {
  /**
   * §4.9's stored number, never a row index.
   *
   * "Update 7 said the moulds were late" is a thing somebody says to support six months
   * later, so the service allocates the number once and never recomputes it. Numbering the
   * list here from its position would renumber every earlier update the first time one was
   * withheld, which is the exact failure that argument exists to prevent.
   */
  readonly number: number;
  readonly title: string;
  readonly body: string;
  readonly visibility: UpdateVisibility;
  /** ISO-8601 instant, UTC. Never in the future — the public read filters on it. */
  readonly publishedAt: string;
  /** Who wrote it, as an account id. There is no public name behind it yet. */
  readonly authorId: string | null;
}

export interface CampaignUpdatePage {
  readonly updates: readonly CampaignUpdate[];
  /**
   * The next page's cursor, or `null` on the last page.
   *
   * An update cursor is an integer — the update number to read back from — rather than the
   * opaque string the community module's other lists use. That is the service's choice and
   * it is passed back unexamined; the only thing this module asserts about it is that a
   * number is a page and `null` is the end.
   */
  readonly nextCursor: number | null;
}

/**
 * How many updates one page asks for.
 *
 * A campaign that ran for two months has perhaps a dozen; twenty covers nearly all of them
 * in one request, and the "Show older updates" link below the list is what covers the
 * campaigns that have been fulfilling for a year.
 */
export const UPDATE_PAGE_SIZE = 20;

/** A minute, matching `lib/api/server.ts` and the service's own `Cache-Control`. */
const PUBLIC_READ_REVALIDATE_SECONDS = 60;

/**
 * One page of a campaign's updates, or `null` when the service refused.
 *
 * `null` rather than an empty page, for the reason `fetchPublicRewards` gives about reward
 * tiers: an empty list is a campaign that has announced nothing, which is a real and
 * different thing, and a tab that could not tell them apart would print "no updates yet"
 * over a service that was merely restarting.
 */
export async function fetchProjectUpdates(
  projectId: string,
  cursor: number | null = null,
  options: ServerReadOptions = {},
): Promise<CampaignUpdatePage | null> {
  const baseUrl = apiOrigin(options.env);
  const client =
    options.fetchImpl === undefined
      ? createApiClient({ baseUrl })
      : createApiClient({ baseUrl, fetch: options.fetchImpl });

  try {
    const body = await client.get('/v1/projects/{projectId}/updates', {
      path: { projectId },
      query: cursor === null ? { limit: UPDATE_PAGE_SIZE } : { cursor, limit: UPDATE_PAGE_SIZE },
      ...(options.locale === undefined ? {} : { headers: { 'accept-language': options.locale } }),
      next: { revalidate: options.revalidateSeconds ?? PUBLIC_READ_REVALIDATE_SECONDS },
    });
    return readUpdatePage(body);
  } catch (cause) {
    /*
     * The same two-case rule `lib/api/server.ts` argues: a refusal the service made is an
     * answer this tab can render, and a `TypeError` from an unreachable service is the one
     * non-refusal a public page still has to survive. Anything else is a bug and is allowed
     * to surface rather than becoming a campaign that has quietly stopped announcing things.
     */
    if (cause instanceof ApiError || cause instanceof TypeError) return null;
    throw cause;
  }
}

const EMPTY_PAGE: CampaignUpdatePage = Object.freeze({ updates: [], nextCursor: null });

/**
 * The wire body, narrowed to something renderable.
 *
 * Every field of the generated type is optional — springdoc marks a record component
 * required only when it can prove it — so the narrowing happens once, here, rather than at
 * every use in the component. <strong>A row missing its number, title or instant is dropped
 * rather than rendered with a blank</strong>: an update is a dated, numbered statement, and
 * one without a date or a number is not a weaker version of that, it is a row this page
 * cannot describe.
 *
 * Exported for the test, which is the only way to state "an unusable row is dropped and the
 * usable ones beside it survive" without a network.
 */
export function readUpdatePage(body: unknown): CampaignUpdatePage {
  if (body === null || typeof body !== 'object') return EMPTY_PAGE;

  const source = body as Record<string, unknown>;
  const rows = source['updates'];
  const cursor = source['nextCursor'];

  const updates: CampaignUpdate[] = [];
  if (Array.isArray(rows)) {
    for (const row of rows as readonly unknown[]) {
      const update = readUpdate(row);
      if (update !== null) updates.push(update);
    }
  }

  return {
    updates,
    nextCursor: typeof cursor === 'number' && Number.isFinite(cursor) ? cursor : null,
  };
}

function readUpdate(value: unknown): CampaignUpdate | null {
  if (value === null || typeof value !== 'object') return null;

  const source = value as Record<string, unknown>;
  const number = source['number'];
  const title = text(source['title']);
  const publishedAt = text(source['publishedAt']);

  if (typeof number !== 'number' || !Number.isFinite(number)) return null;
  if (title === null || publishedAt === null) return null;

  return {
    number,
    title,
    /*
     * The body may legitimately be empty — a one-line announcement is a title and nothing
     * else — so it falls back to an empty string rather than disqualifying the row. The
     * title and the date are what make an update an update; the body is what it says.
     */
    body: text(source['body']) ?? '',
    visibility: readVisibility(source['visibility']),
    publishedAt,
    authorId: text(source['authorId']),
  };
}

/**
 * `PUBLIC` unless the service said otherwise.
 *
 * Deliberately the safe default for the <em>reader</em> rather than for the platform: this
 * list has already been filtered by the service, so an unrecognised value is a row the
 * service chose to send. Marking it backers-only on a guess would print "Backers only"
 * beside an update everybody can see, which is a claim about who else is reading.
 */
function readVisibility(value: unknown): UpdateVisibility {
  return value === 'BACKERS_ONLY' ? 'BACKERS_ONLY' : 'PUBLIC';
}

function text(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}
