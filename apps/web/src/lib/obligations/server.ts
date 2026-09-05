import type { EnvSource } from '../seo/metadata';
import { apiOrigin } from '../seo/metadata-source';
import { project as projectTag, profile as profileTag } from '../cache/tags';
import {
  readCreatorObligations,
  readUpdateObligation,
  type CreatorObligations,
  type UpdateObligation,
} from './api';

/**
 * The obligation reads, on the server — issue #437, surfaced by #439.
 *
 * <h2>Anonymous, always</h2>
 *
 * `credentials: 'omit'` and no `Authorization` header, following `lib/profiles/server.ts`. The
 * whole point of §22.3's mechanism is that the fact is visible to a stranger deciding whether
 * to back this creator again, so a read that varied by session would be a page no shared cache
 * could hold and a fact that showed itself to the wrong audience.
 *
 * <h2>A minute, and the tag is what makes it survivable</h2>
 *
 * The same window every public read in this application carries. What moves an obligation is a
 * creator publishing an update, which already invalidates that campaign's tag — so a creator
 * who posts sees their own campaign page stop calling them late, rather than waiting out a
 * window on the one page where being wrong costs them something.
 *
 * <h2>Failure is `null`, and it draws nothing</h2>
 *
 * Unlike the profile's lists, `null` here does not become a panel that says the read failed. A
 * campaign with no clock — one that is live, was unsuccessful, or closed before the mechanism
 * existed — answers **204**, which lands here as `null` too, and the two are indistinguishable
 * on purpose: both mean "there is nothing to say about this campaign's updates", and a page
 * that told a reader an obligation could not be loaded would be inventing an obligation to
 * apologise for.
 */

export interface ObligationReadOptions {
  /** Injected in tests. Defaults to the platform `fetch`. */
  readonly fetchImpl?: typeof fetch;
  readonly env?: EnvSource;
  readonly signal?: AbortSignal;
}

const OBLIGATION_REVALIDATE_SECONDS = 60;

async function read(path: string, tag: string, options: ObligationReadOptions): Promise<unknown | null> {
  const fetchImpl = options.fetchImpl ?? fetch;

  try {
    const response = await fetchImpl(`${apiOrigin(options.env)}${path}`, {
      credentials: 'omit',
      headers: { accept: 'application/json' },
      next: { revalidate: OBLIGATION_REVALIDATE_SECONDS, tags: [tag] },
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });

    // 204 is "this campaign has no clock", which is the ordinary case and not a failure. It has
    // no body, so it is answered here rather than being handed to a JSON parse that would throw.
    if (response.status === 204) return null;
    if (!response.ok) return null;

    return (await response.json()) as unknown;
  } catch {
    // A network failure, an abort, or a body that was not the JSON it claimed to be. None of
    // those is something a public page should turn into a 500 — and here, none of them is worth
    // telling a reader about either. See the module comment.
    return null;
  }
}

/** One campaign's obligation — `GET /v1/projects/{id}/update-obligation`. */
export async function fetchUpdateObligation(
  projectId: string,
  options: ObligationReadOptions = {},
): Promise<UpdateObligation | null> {
  const body = await read(
    `/v1/projects/${encodeURIComponent(projectId)}/update-obligation`,
    projectTag(projectId),
    options,
  );
  if (body === null) return null;

  return readUpdateObligation(body);
}

/**
 * A creator's history — `GET /v1/users/{slug}/update-obligations`.
 *
 * <strong>By slug and not by identifier</strong>, because that is what this page has: §4.2's
 * profile projection deliberately carries no identifier, so a page that has just rendered
 * somebody's profile knows their address and nothing else. The service offers both addresses
 * for one read and resolves the slug itself; doing the lookup here would be the client
 * repeating work the service already does, over an identifier it is not given.
 *
 * Filed under the profile's tag rather than under a tag of its own: it is drawn on the profile
 * and nowhere else, and a second tag would be one more thing the service has to remember to
 * send.
 */
export async function fetchCreatorObligations(
  slug: string,
  options: ObligationReadOptions = {},
): Promise<CreatorObligations | null> {
  const body = await read(
    `/v1/users/${encodeURIComponent(slug)}/update-obligations`,
    profileTag(slug),
    options,
  );
  if (body === null) return null;

  return readCreatorObligations(body);
}
