import { isCacheTag } from './tags';

/**
 * `POST /api/cache/revalidate` — how the service tells this application that something it
 * has cached is no longer true. Issue #127.
 *
 * <h2>Why the service pushes rather than this application polling</h2>
 *
 * The events that make a cached public page wrong all happen inside the service and most of
 * them never pass through this application at all. A pledge is confirmed by a provider
 * callback; a scheduled update becomes public on a sweep; a campaign closes on its deadline.
 * There is no request to this application at any of those moments, so the only alternative to
 * being told is asking — which is a poll per campaign per interval, and is the cost the cache
 * exists to avoid.
 *
 * <h2>The secret, and what it is and is not for</h2>
 *
 * A shared secret in `IDEANEST_REVALIDATE_SECRET`, compared in constant time, required on
 * every call. It is not protecting confidential data — the tags name public pages — it is
 * protecting the cache itself: an unauthenticated endpoint that evicts by name is a request
 * anybody can send in a loop to turn every cached render into an origin fetch. That is a
 * denial-of-service against the service this cache exists to shield, delivered through the
 * one door built to be fast.
 *
 * <p><strong>With no secret configured the endpoint refuses everything.</strong> Not "allows
 * everything in development": a deployment that lost the variable would come up with the door
 * open and nothing would say so. A 503 that names the missing configuration is the failure
 * somebody notices.
 *
 * <h2>The vocabulary is closed, and unknown tags are named rather than ignored</h2>
 *
 * `lib/cache/tags.ts` decides what a tag may look like. A caller with the secret and a bug —
 * an empty string, a wildcard, a tag built out of an unescaped title — is the realistic
 * threat here, and the first two of those evict everything. Anything unrecognised is refused
 * and listed in the response body, because a caller whose tag was silently dropped would
 * believe the page had been refreshed.
 *
 * <h2>At-least-once, and that is fine</h2>
 *
 * The relay on the other side retries, so the same invalidation arrives more than once.
 * Evicting a tag twice costs one extra origin fetch and nothing else, so there is no
 * idempotency key here and no record of what has been seen — which would be a second store to
 * keep, protecting against a cost the system already absorbs.
 */

/** At most this many tags in one call. A batch is a campaign's worth, not a platform's. */
export const MAX_TAGS = 32;

export interface RevalidateDependencies {
  /** `revalidateTag` from `next/cache`, injected so the handler is testable off a request. */
  readonly revalidate: (tag: string) => void;
  /** The configured secret, or `undefined` when the deployment has none. */
  readonly secret: string | undefined;
}

export interface RevalidateOutcome {
  readonly status: number;
  readonly body: Record<string, unknown>;
}

/**
 * Handles one call. Everything about the endpoint is here; the route file gives it a URL.
 *
 * <p>Returns rather than throws for every refusal, so the route has one shape and the status
 * is decided in one place.
 */
export async function handleRevalidate(
  request: Request,
  dependencies: RevalidateDependencies,
): Promise<RevalidateOutcome> {
  const { secret } = dependencies;
  if (secret === undefined || secret === '') {
    return {
      status: 503,
      body: {
        error: 'not-configured',
        detail: 'IDEANEST_REVALIDATE_SECRET is not set, so no caller can be authenticated.',
      },
    };
  }

  if (!matches(bearerOf(request), secret)) {
    return { status: 401, body: { error: 'unauthorised' } };
  }

  const tags = tagsOf(await bodyOf(request));
  if (tags === null) {
    return {
      status: 400,
      body: { error: 'bad-request', detail: 'Expected a JSON body of the shape {"tags": [...]}.' },
    };
  }
  if (tags.length === 0) {
    return { status: 400, body: { error: 'bad-request', detail: 'No tags were given.' } };
  }
  if (tags.length > MAX_TAGS) {
    return {
      status: 400,
      body: { error: 'too-many-tags', detail: `At most ${MAX_TAGS} tags in one call.` },
    };
  }

  const unknown = tags.filter((tag) => !isCacheTag(tag));
  if (unknown.length > 0) {
    // Named rather than dropped: a caller whose tag was silently ignored would believe the
    // page had been refreshed, and would go looking for the bug on the wrong side.
    return { status: 400, body: { error: 'unknown-tags', tags: unknown } };
  }

  for (const tag of tags) dependencies.revalidate(tag);

  return { status: 200, body: { revalidated: tags } };
}

/** The token from `Authorization: Bearer …`, or null. */
function bearerOf(request: Request): string | null {
  const header = request.headers.get('authorization');
  if (header === null) return null;

  const [scheme, ...rest] = header.split(' ');
  if (scheme?.toLowerCase() !== 'bearer') return null;

  const token = rest.join(' ').trim();
  return token === '' ? null : token;
}

/**
 * Constant-time comparison.
 *
 * <p>A `===` on a secret leaks its prefix through how long the comparison takes, and a caller
 * who can measure that can find the secret one character at a time. The cost of not caring is
 * an attacker who can evict this application's entire cache on demand; the cost of caring is
 * the loop below.
 *
 * <p>The length is compared first and separately, which does leak the length — unavoidable
 * without hashing, and a secret's length is not the secret.
 */
function matches(candidate: string | null, secret: string): boolean {
  if (candidate === null || candidate.length !== secret.length) return false;

  let difference = 0;
  for (let index = 0; index < secret.length; index += 1) {
    difference |= candidate.charCodeAt(index) ^ secret.charCodeAt(index);
  }
  return difference === 0;
}

async function bodyOf(request: Request): Promise<unknown> {
  try {
    return (await request.json()) as unknown;
  } catch {
    // A body that is not JSON is a caller error, not an exception this endpoint reports.
    return null;
  }
}

/** The tags in a body, or `null` when the body is not the shape this endpoint takes. */
function tagsOf(body: unknown): readonly string[] | null {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) return null;

  const rows = (body as Record<string, unknown>)['tags'];
  if (!Array.isArray(rows)) return null;
  if (!rows.every((row) => typeof row === 'string')) return null;

  // Duplicates are dropped rather than refused. A relay that retried half a batch is a
  // caller doing the right thing, and evicting one tag twice is not an error.
  return [...new Set(rows as readonly string[])];
}
