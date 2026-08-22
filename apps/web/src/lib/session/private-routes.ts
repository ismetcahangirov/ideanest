/**
 * Which routes a visitor holding no session may not reach — §4.1, §17.
 *
 * <h2>This is not `PRIVATE_PATH_PREFIXES`, and the difference is deliberate</h2>
 *
 * `lib/seo/indexability.ts` owns a list with a similar name that answers a different
 * question: *may a crawler index this*. The two overlap and they are not the same, exactly
 * as that module argues "public" and "indexable" are not the same. Two entries prove it:
 *
 *   - `/projects/*&#47;prelaunch` is in the crawler's list and must not be here. The
 *     pre-launch page exists for the followers it collects, who have not registered — a
 *     guard on it would turn "tell me when this opens" into "sign in first", which is the
 *     funnel a pre-launch page is built to avoid.
 *   - `/projects/*&#47;back` is likewise absent. `apps/web/README.md` calls it the half-way
 *     case: the reward list is `permitAll` and reads through `publicFetch`, so the prices
 *     render for somebody who has not registered and only the two mutations need a token.
 *     Redirecting at the door would hide the prices from the audience the page is for.
 *
 * So the lists are stated separately and neither is derived from the other. Deriving one
 * would mean a change made for a crawler silently changing who can read a page.
 *
 * <h2>A prefix, and a `*` for one segment</h2>
 *
 * The same shape `PRIVATE_PATH_PREFIXES` uses, for the same reason: a campaign id sits in
 * the middle of several of these paths and a list of literals cannot express that. `*`
 * matches exactly one segment and never a `/`, so `/projects/*&#47;edit` covers
 * `/projects/abc/edit` and `/projects/abc/edit/story` but never `/projects/abc`.
 */
export const SESSION_REQUIRED_PATHS: readonly string[] = Object.freeze([
  // The account and its settings — §4.1's A-09 device list is on one of them, and §4.2's
  // preferences on another.
  '/settings',
  '/account',

  // The in-app notification inbox (§4.10). Every row in it is addressed to one person.
  '/notifications',

  // The campaign editor: somebody's unpublished work (§4.6).
  '/projects/new',
  '/projects/*/edit',

  // The creator dashboard (§4.7) and the pledge manager (§4.8).
  '/projects/*/dashboard',
  '/pledges',

  /*
   * Administration (§4.11). A SESSION IS THE FLOOR HERE AND NOT THE RULE — these routes
   * want a staff role, which is #295, and this guard cannot express one because the web
   * client has no roles yet. What it does is stop an anonymous visitor from loading a
   * console shell that will only ever answer 403; the service refuses the reads either way,
   * and it is the service that decides, not this list.
   */
  '/admin',
]);

/**
 * Whether reaching this path without a session is pointless.
 *
 * THE PATH ONLY, never the query string. Nothing in the list is distinguished by one, and a
 * matcher that looked at `?next=` would be a matcher an attacker can aim.
 */
export function requiresSession(pathname: string): boolean {
  return SESSION_REQUIRED_PATHS.some((pattern) => matches(pattern, pathname));
}

function matches(pattern: string, pathname: string): boolean {
  const patternSegments = pattern.split('/');
  const pathSegments = pathname.split('/');

  // A prefix: the path may be longer than the pattern, never shorter.
  if (pathSegments.length < patternSegments.length) return false;

  return patternSegments.every(
    (segment, index) => segment === '*' || segment === pathSegments[index],
  );
}
