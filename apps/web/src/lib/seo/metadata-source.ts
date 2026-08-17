import { isPubliclyVisible, type EnvSource, type PublicProjectPreview } from './metadata';
import type { ProjectState } from '../projects/api';

/**
 * Where `generateMetadata` and the Open Graph image routes get their facts.
 *
 * <h2>Why this is not `lib/api/client.ts`</h2>
 *
 * `publicFetch` is the browser's client: it calls a RELATIVE path, because
 * `next.config.mjs` proxies `/v1` through this application's own origin so that a
 * `SameSite=Strict` cookie can travel at all, and it attaches the access token
 * held in memory when there is one. Neither applies here. This code runs on the
 * server, where a relative URL is not a URL, and it runs for a request that has
 * no session — so it talks to the service directly, at the origin the proxy
 * points at.
 *
 * <h2>And why it sends no credentials, on purpose</h2>
 *
 * WHAT A LINK PREVIEW SHOWS IS WHAT AN ANONYMOUS REQUEST ANSWERS. A crawler holds
 * no session and a chat client unfurling a pasted link holds somebody else's, so
 * building a card from an authenticated projection would mean the card said more
 * than its audience is allowed to read — and the first person to notice would be
 * a creator whose unannounced campaign was quoted back at them by a search
 * engine. `credentials: 'omit'` and no `Authorization` header make that
 * structurally impossible rather than merely unintended.
 *
 * <h2>It never throws</h2>
 *
 * A `generateMetadata` that throws fails the whole route. A campaign page with no
 * social card is a worse page; a campaign page that 500s because a `<meta>` tag
 * could not be written is a lost backer. Every failure — a refusal, an outage,
 * a body that is not the JSON it claimed to be — is the same answer: `null`, which
 * callers treat as "not public" and therefore `noindex`.
 */

/** The service, as `next.config.mjs` reads it. Same variable, same default. */
export function apiOrigin(env: EnvSource = process.env): string {
  const configured = env['IDEANEST_API_ORIGIN']?.trim();
  const origin = configured === undefined || configured === '' ? 'http://localhost:8080' : configured;

  return origin.endsWith('/') ? origin.slice(0, -1) : origin;
}

function readString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

function readCoverImage(value: unknown): PublicProjectPreview['coverImage'] {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;

  const source = value as Record<string, unknown>;
  const url = readString(source, 'url');
  const { width, height } = source;

  /*
   * The dimensions are required rather than guessed. An unfurler that is told the
   * size lays the card out before the image arrives; one that is told a wrong
   * size lays it out wrong, and §5.3 already makes the two columns a submission
   * requirement, so a cover without them is a row that should not exist.
   */
  if (url === null) return null;
  if (typeof width !== 'number' || typeof height !== 'number') return null;
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null;

  return { url, width, height };
}

/**
 * The campaign in a response body, or `null` if there is not one this may print.
 *
 * NARROWED FIELD BY FIELD, never spread. The response is the pre-launch
 * projection today and the campaign projection when §4.4 is built, and both will
 * grow fields; a spread would carry whichever one a future deployment adds into a
 * social card that nobody decided should carry it. Six fields are copied out by
 * name and the rest of the body is discarded.
 *
 * `title` is required, because a card with no title is not a card. Everything
 * else has a defined absence: the service serialises with
 * `default-property-inclusion: non_null`, so an unset summary has no key at all
 * rather than a null one, and both readings mean the same thing here (the rule
 * `lib/projects/api.ts` states).
 */
export function readPublicProjectPreview(body: unknown): PublicProjectPreview | null {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) return null;

  const source = body as Record<string, unknown>;
  const state = readString(source, 'state');
  const title = readString(source, 'title');
  const id = readString(source, 'id');
  const slug = readString(source, 'slug');

  if (state === null || title === null || id === null || slug === null) return null;
  if (!isPubliclyVisible(state)) return null;

  return {
    id,
    slug,
    state: state as ProjectState,
    title,
    blurb: readString(source, 'blurb'),
    coverImage: readCoverImage(source['coverImage']),
  };
}

export interface PreviewFetchOptions {
  /** Injected in tests. Defaults to the platform `fetch`. */
  readonly fetchImpl?: typeof fetch;
  readonly env?: EnvSource;
  readonly signal?: AbortSignal;
}

/**
 * Five minutes.
 *
 * A shared link does not need a second-accurate title, and a campaign that goes
 * viral would otherwise put one request on the service for every unfurl of the
 * same URL. Five minutes is short enough that a creator who fixes a typo and
 * re-shares sees the fix, and long enough that a thousand people pasting the
 * same link cost one request.
 */
const PREVIEW_REVALIDATE_SECONDS = 300;

/**
 * The public projection of one campaign, or `null` — see the module comment.
 *
 * `GET /v1/projects/{id}/prelaunch` is the only public campaign read this
 * application has today: it is `permitAll` and it 404s for a campaign in any
 * state but `PRELAUNCH` or `SCHEDULED`. `GET /v1/projects/{creatorSlug}/{projectSlug}`
 * — the campaign page's own projection, docs/architecture.md §10.2 — is #119's,
 * and the day it exists this function grows a second branch rather than a second
 * copy of itself.
 */
export async function fetchPublicProjectPreview(
  id: string,
  options: PreviewFetchOptions = {},
): Promise<PublicProjectPreview | null> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const url = `${apiOrigin(options.env)}/v1/projects/${encodeURIComponent(id)}/prelaunch`;

  try {
    const response = await fetchImpl(url, {
      // See the module comment: an anonymous request, deliberately.
      credentials: 'omit',
      headers: { accept: 'application/json' },
      next: { revalidate: PREVIEW_REVALIDATE_SECONDS },
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });

    if (!response.ok) return null;

    return readPublicProjectPreview(await response.json());
  } catch {
    return null;
  }
}
