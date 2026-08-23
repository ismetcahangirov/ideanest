import { ApiError, createApiClient } from '@ideanest/api-client';
import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { ServerReadOptions } from '../api/server';
import { apiOrigin } from '../seo/metadata-source';

/**
 * §4.4's Comments tab — §4.9's C-01, C-02 and C-03, behind the community module's endpoints.
 *
 * <h2>The list is read on the server; the writes are the browser's</h2>
 *
 * `GET /v1/projects/{projectId}/comments` is `permitAll`, and a conversation under a
 * campaign is public content that a search engine and a link unfurler are entitled to. So
 * the read happens in the Server Component, in the initial HTML, like the story above it.
 *
 * The three writes cannot be. Each needs the caller's bearer token, and §4.9's rule that
 * `by_creator` is settled by the server at write time means the client has nothing to decide
 * — it posts a body and re-reads. They go through `authorizedFetch`, which is the browser's
 * path and the only one that carries a session.
 *
 * <strong>Both halves live in one module on purpose.</strong> The alternative — a
 * `comments.server.ts` and a `comments.client.ts` — would need the wire shapes declared in a
 * third file to avoid a copy, and the copy is the thing that goes wrong: a reader that
 * forgets `deleted` renders a tombstone as a blank comment. The cost is that a bundler has
 * to tree-shake `fetchCommentThreads` out of the browser build, which it can: the module has
 * no top-level side effects and every export is a function.
 *
 * <h2>Two levels, and the client places the control rather than discovering the rule</h2>
 *
 * §4.9: a reply answers a root, a reply to a reply is a 422, and every row carries
 * `acceptsReplies` so that a client puts the reply control where a reply is allowed instead
 * of finding out by being refused. That flag is read below and it is what the reply control
 * keys on — never `depth === 0` recomputed here, which would be a second copy of the bound
 * that the day it disagreed would offer somebody a form the server was going to reject.
 *
 * <h2>A tombstone is a row, not an absence</h2>
 *
 * §4.9: deleting a comment sets `deleted_at` and the read serves `body: null`,
 * `authorId: null`, `deleted: true`. The row stays because replies must not be orphaned,
 * because a moderator holding a report has to be able to read what it said, and because
 * "removed" printed beside a name is an accusation published to everybody on the page.
 * {@link CampaignComment.deleted} is therefore a first-class field here and the component
 * renders it as a tombstone — dropping the row would break the first of those three and
 * silently renumber a conversation somebody screenshotted.
 *
 * <h2>Why the read is here rather than in `lib/api/server.ts`</h2>
 *
 * `lib/community/updates.ts` gives the argument in full: same conventions, different file,
 * because that module is shared ground in this branch and this one is not.
 */

/**
 * One comment, as the public read serves it.
 *
 * `body` and `authorId` are nullable because a tombstone has neither — that is the shape
 * §4.9 specifies, and typing them non-null with an empty-string fallback would let a
 * component print an empty quotation mark where a removal notice belongs.
 */
export interface CampaignComment {
  readonly id: string;
  /** The root this belongs under. A root's own identifier for a root. */
  readonly threadId: string;
  /** `null` for a root. */
  readonly parentId: string | null;
  /** `null` on a tombstone, and on nothing else. */
  readonly authorId: string | null;
  /** `null` on a tombstone. */
  readonly body: string | null;
  /** §4.9's C-02, decided by the server at write time and never by the request body. */
  readonly byCreator: boolean;
  readonly deleted: boolean;
  /** `0` for a root, `1` for a reply. The bound is structural — see the module comment. */
  readonly depth: number;
  /** ISO-8601 instant, UTC. */
  readonly createdAt: string;
  /** Whether the service would accept a reply to this row. Read, never recomputed. */
  readonly acceptsReplies: boolean;
}

export interface CampaignCommentThread {
  readonly root: CampaignComment;
  readonly replies: readonly CampaignComment[];
  /** More replies under this root than the page carried, or `null`. Opaque; never parsed. */
  readonly nextReplyCursor: string | null;
}

export interface CampaignCommentPage {
  readonly threads: readonly CampaignCommentThread[];
  /** The next page of conversations, or `null` on the last. Opaque; never parsed. */
  readonly nextCursor: string | null;
}

/**
 * How many conversations one page asks for.
 *
 * Smaller than the updates page, because a thread is a root plus its replies rather than one
 * row: ten conversations is already a long tab, and the service decides how many replies
 * come with each.
 */
export const COMMENT_PAGE_SIZE = 10;

/** A minute, matching `lib/api/server.ts` and the service's own `Cache-Control`. */
const PUBLIC_READ_REVALIDATE_SECONDS = 60;

/**
 * Which part of a campaign's conversations to read.
 *
 * <strong>Two reads on one route, which is the service's design and not this module's.</strong>
 * Without `thread` the endpoint answers the tab: a page of conversations, each with a preview
 * of its replies. With it, one conversation and a page of that conversation's replies — which
 * is what "show more replies" asks for. `PublicCommentController` argues why it is a
 * parameter rather than a second route, and a second function here would be a second place to
 * keep that in step.
 */
export interface CommentPageLocation {
  /** The `nextCursor` (or `nextReplyCursor`) from the previous read. Opaque; never parsed. */
  readonly cursor?: string | null | undefined;
  /** A root comment's identifier, to read that one conversation in full. */
  readonly thread?: string | null | undefined;
}

/**
 * One page of a campaign's conversations, or `null` when the service refused.
 *
 * `null` rather than an empty page, for the reason the reward list gives: a campaign nobody
 * has commented on is a real and different thing from a service that could not be reached,
 * and a tab that could not tell them apart would print "nobody has commented" over an
 * outage.
 *
 * <strong>The server read is anonymous.</strong> `lib/api/server.ts` argues why at length,
 * and the service's own controller says the cost is nil here: a comment has no
 * backers-only variant and no scheduling, so the campaign's team is served the same page as
 * a visitor and the body is shareable. The one thing a token would add on this endpoint is
 * reading the comments under a campaign that is not public yet, which is the creator's
 * dashboard rather than this page.
 */
export async function fetchCommentThreads(
  projectId: string,
  location: CommentPageLocation = {},
  options: ServerReadOptions = {},
): Promise<CampaignCommentPage | null> {
  const baseUrl = apiOrigin(options.env);
  const client =
    options.fetchImpl === undefined
      ? createApiClient({ baseUrl })
      : createApiClient({ baseUrl, fetch: options.fetchImpl });

  const { cursor, thread } = location;

  try {
    const body = await client.get('/v1/projects/{projectId}/comments', {
      path: { projectId },
      query: {
        limit: COMMENT_PAGE_SIZE,
        ...(thread == null ? {} : { thread }),
        ...(cursor == null ? {} : { cursor }),
      },
      ...(options.locale === undefined ? {} : { headers: { 'accept-language': options.locale } }),
      next: { revalidate: options.revalidateSeconds ?? PUBLIC_READ_REVALIDATE_SECONDS },
    });
    return readCommentPage(body);
  } catch (cause) {
    // The two-case rule `lib/api/server.ts` argues: a refusal is an answer, an unreachable
    // service is the failure a public page survives, anything else is a bug worth surfacing.
    if (cause instanceof ApiError || cause instanceof TypeError) return null;
    throw cause;
  }
}

const EMPTY_PAGE: CampaignCommentPage = Object.freeze({ threads: [], nextCursor: null });

/**
 * The wire body, narrowed.
 *
 * A thread with no readable root is dropped: the replies under it hang off nothing this page
 * could render, and a conversation whose first message is missing is not a shorter
 * conversation. A single unreadable <em>reply</em> is dropped on its own and the rest of its
 * thread survives, because the conversation is still a conversation without it.
 *
 * Exported for the test, which is the only way to state those two rules without a network.
 */
export function readCommentPage(body: unknown): CampaignCommentPage {
  if (body === null || typeof body !== 'object') return EMPTY_PAGE;

  const source = body as Record<string, unknown>;
  const rows = source['threads'];

  const threads: CampaignCommentThread[] = [];
  if (Array.isArray(rows)) {
    for (const row of rows as readonly unknown[]) {
      const thread = readThread(row);
      if (thread !== null) threads.push(thread);
    }
  }

  return { threads, nextCursor: text(source['nextCursor']) };
}

function readThread(value: unknown): CampaignCommentThread | null {
  if (value === null || typeof value !== 'object') return null;

  const source = value as Record<string, unknown>;
  const root = readComment(source['root']);
  if (root === null) return null;

  const replies: CampaignComment[] = [];
  const rows = source['replies'];
  if (Array.isArray(rows)) {
    for (const row of rows as readonly unknown[]) {
      const reply = readComment(row);
      if (reply !== null) replies.push(reply);
    }
  }

  return { root, replies, nextReplyCursor: text(source['nextReplyCursor']) };
}

function readComment(value: unknown): CampaignComment | null {
  if (value === null || typeof value !== 'object') return null;

  const source = value as Record<string, unknown>;
  const id = text(source['id']);
  const createdAt = text(source['createdAt']);
  if (id === null || createdAt === null) return null;

  const deleted = source['deleted'] === true;
  const body = text(source['body']);

  /*
   * A row with neither a body nor a deletion flag is not renderable. The service sends one
   * or the other on every row it serves, so this is a malformed response rather than an
   * empty comment — and printing a blank speech bubble under somebody's campaign is worse
   * than printing nothing.
   */
  if (!deleted && body === null) return null;

  return {
    id,
    // A root heads its own thread, which is what the service stores, so an absent
    // `threadId` falls back to the row's own identifier rather than disqualifying it.
    threadId: text(source['threadId']) ?? id,
    parentId: text(source['parentId']),
    authorId: text(source['authorId']),
    body: deleted ? null : body,
    byCreator: source['byCreator'] === true,
    deleted,
    depth: typeof source['depth'] === 'number' ? (source['depth'] as number) : 0,
    createdAt,
    acceptsReplies: source['acceptsReplies'] === true,
  };
}

function text(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

/* -------------------------------------------------------------------------
 * The three writes — the browser's half
 *
 * All three need a session, and §4.9 gives the reason it is a mechanism rather than
 * friction: who may comment is "backers of that project and its creator", enforced today as
 * far as "a signed-in account in good standing", and the duplicate suppression the whole
 * feature rests on is unstateable without an identity. So `authorizedFetch`, which throws a
 * 401 when there is no token, is the right shape — a composer is never offered to somebody
 * who has no session, and `ReportControl` argues that same point for its own form.
 *
 * NONE OF THEM RETURNS THE NEW LIST, and the callers do not build one. The component calls
 * `router.refresh()` afterwards and the server re-renders the page it already knows how to
 * render. Splicing the new comment into local state would be a second, client-side
 * implementation of thread ordering, reply nesting and the creator highlight — three things
 * §4.9 settles on the server precisely so that a client cannot get them wrong.
 * ---------------------------------------------------------------------- */

const JSON_HEADERS = { 'content-type': 'application/json' } as const;

/**
 * Whether a body is worth sending.
 *
 * <strong>A friendliness, not a rule this module owns.</strong> The service stores the body
 * on a `not null` column and §10.2 publishes no length bound, so this refuses only the one
 * case that is certainly a mistake — nothing typed at all — and lets the service be the
 * authority on everything else. A client-side maximum invented here would be a limit nobody
 * could find in the contract, refusing a long comment the platform would have accepted.
 */
export function isSubmittableComment(body: string): boolean {
  return body.trim() !== '';
}

/** §4.9's C-01 — a new conversation. `POST /v1/projects/{projectId}/comments`. */
export async function postComment(projectId: string, body: string): Promise<CampaignComment | null> {
  const response = await authorizedFetch(`/v1/projects/${encodeURIComponent(projectId)}/comments`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ body }),
  });
  if (!response.ok) throw await errorFrom(response);
  return readComment(await response.json());
}

/**
 * §4.9's C-03 — an answer to a root. `POST /v1/comments/{commentId}/reply`.
 *
 * Addressed by the comment being answered rather than by the campaign, which is what makes
 * the two-level bound checkable in one query on the server side: the parent's depth is on
 * the row the path names.
 */
export async function replyToComment(commentId: string, body: string): Promise<CampaignComment | null> {
  const response = await authorizedFetch(`/v1/comments/${encodeURIComponent(commentId)}/reply`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ body }),
  });
  if (!response.ok) throw await errorFrom(response);
  return readComment(await response.json());
}

/**
 * Withdraws a comment — `DELETE /v1/comments/{commentId}`.
 *
 * <strong>Not a removal, and the interface must not call it one.</strong> §4.9: the row
 * stays, its body stays, and the read serves a tombstone. It is idempotent, so a retry
 * cannot rewrite who removed it, and it spends none of the comment rate limit — a creator
 * clearing a flood must not be stopped part way through by the control that exists to stop
 * the flood.
 *
 * There is no edit endpoint, deliberately (§4.9), so this is the whole of "I take that
 * back".
 */
export async function deleteComment(commentId: string): Promise<void> {
  const response = await authorizedFetch(`/v1/comments/${encodeURIComponent(commentId)}`, {
    method: 'DELETE',
  });
  if (!response.ok) throw await errorFrom(response);
}
