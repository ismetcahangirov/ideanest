import type { Page } from '../community/signals';
import type { ProfileProjectCard, PublicProfile } from '../profiles/api';
import { profileHref } from '../profiles/api';
import { fetchPublicProfile as readProfile, type ProfileReadOptions } from '../profiles/server';
import { profile as profileTag } from '../cache/tags';
import { readProjectCardPage } from '../profiles/wire';
import type { EnvSource } from '../seo/metadata';
import { apiOrigin } from '../seo/metadata-source';

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
 * <h2>ONE READER FOR THE PROFILE, AND IT IS NOT THIS FILE — #323</h2>
 *
 * This module and `lib/profiles/` were written in parallel during #321 and both read
 * `GET /v1/users/{slug}`. Neither was wrong and neither survived as it was: the narrowing this
 * file had — field by field, with a test — moved to `lib/profiles/wire.ts`, and the anonymous
 * server read this file had written out longhand moved to `lib/profiles/server.ts`, which was
 * already sending the `credentials: 'omit'` that this one only argued for in a comment.
 *
 * <p>What is left here is what the Creator tab actually needs on top of that: a shorter list
 * than a profile page shows, asked for with one extra row so that dropping the campaign the
 * reader is already on still leaves the tab full. The types and the address are re-exported
 * rather than restated, so a component importing `PublicProfile` from here and one importing
 * it from `lib/profiles/api.ts` cannot come to mean two different things.
 *
 * <h2>404 is the whole of the error handling, and that is by design</h2>
 *
 * The profile endpoint answers 404 — never 403 — for an unknown slug, a deleted account and
 * an account whose `profile_visibility` is `PRIVATE`. That is an oracle-free refusal: a 403
 * would confirm that a slug belongs to somebody who has chosen to be private, which is
 * exactly what choosing to be private is meant to prevent. So every refusal is `null` alike,
 * and the tab renders what the campaign response already carries — the creator's name and
 * avatar — with no profile link and no explanation of why there is none. A sentence saying
 * "this creator's profile is private" would rebuild the oracle in the interface.
 */

/** §4.2's public profile. One shape, defined in `lib/profiles/api.ts`. */
export type { PublicProfile } from '../profiles/api';

/**
 * One campaign on a creator's list.
 *
 * `ProfileProjectCard` under the name this tab's components already use. It was a separate
 * interface with the same fields until #323; the only difference was that this one narrowed
 * `state` to `ProjectState`, which claimed a closed vocabulary the service is free to add to.
 * `CreatorPanel` already renders an unrecognised state as its raw identifier rather than as a
 * blank, so widening it costs nothing and stops a tenth state from being a type error in the
 * one place that handles it correctly.
 */
export type CreatorProject = ProfileProjectCard;

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

/** A minute, matching `lib/profiles/server.ts` and the service's own `Cache-Control`. */
const PUBLIC_READ_REVALIDATE_SECONDS = 60;

export interface CreatorReadOptions {
  /** Injected in tests. Defaults to the platform `fetch`. */
  readonly fetchImpl?: typeof fetch;
  readonly env?: EnvSource;
  readonly revalidateSeconds?: number;
}

/** The public address of a creator's profile. One spelling, in `lib/profiles/api.ts`. */
export { profileHref };

/** The creator's profile, or `null` for every refusal alike. */
export function fetchPublicProfile(
  slug: string,
  options: CreatorReadOptions = {},
): Promise<PublicProfile | null> {
  const forwarded: ProfileReadOptions = {
    ...(options.fetchImpl === undefined ? {} : { fetchImpl: options.fetchImpl }),
    ...(options.env === undefined ? {} : { env: options.env }),
  };
  return readProfile(slug, forwarded);
}

/**
 * The creator's public campaigns, newest first, or `null` for every refusal alike.
 *
 * The service already restricts this to §6.1's nine public states, so nothing below filters
 * by state. The campaign currently being read is dropped by the component rather than here:
 * this function answers "what has this person made", and which of those the reader is
 * already looking at is the caller's question.
 *
 * <p>Not `lib/profiles/server.ts`'s `fetchCreatedProjects`, and the difference is the whole
 * reason this function exists: that one asks for a profile page's twenty-four, and this one
 * asks for {@link CREATOR_PROJECT_LIMIT} plus the row the tab is about to remove.
 */
export async function fetchCreatorProjects(
  slug: string,
  options: CreatorReadOptions = {},
): Promise<CreatorProjectPage | null> {
  const impl = options.fetchImpl ?? fetch;
  const path = `/v1/users/${encodeURIComponent(slug)}/projects?limit=${CREATOR_PROJECT_LIMIT + 1}`;

  try {
    const response = await impl(`${apiOrigin(options.env)}${path}`, {
      credentials: 'omit',
      headers: { accept: 'application/json' },
      // The same tag the profile page's own read carries — #127. One creator publishing a
      // campaign should refresh both the Creator tab and `/u/{slug}`, not one of them.
      next: {
        revalidate: options.revalidateSeconds ?? PUBLIC_READ_REVALIDATE_SECONDS,
        tags: [profileTag(slug)],
      },
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
    return pageOf(readProjectCardPage((await response.json()) as unknown));
  } catch {
    /*
     * A service that cannot be reached is the case a public page must survive, and `fetch`
     * reports it as a bare `TypeError` with no status to inspect. The Creator tab degrades
     * to the campaign's own `{slug, name, avatarUrl}`, which is still a truthful byline.
     */
    return null;
  }
}

/** `items` as the tab's `projects`. `useCursorList`'s name on one side, the wire's on the other. */
function pageOf(page: Page<ProfileProjectCard>): CreatorProjectPage {
  return { projects: page.items, nextCursor: page.nextCursor };
}
