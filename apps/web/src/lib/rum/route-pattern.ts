/**
 * The one field in the payload that could have carried a person in it, and the
 * rule that stops it.
 *
 * A real pathname is `/projects/019432f1-…/back`. That identifier names a
 * campaign, the campaign names a creator, and a run of them in timestamp order
 * names what one visitor looked at — so a URL is not attribution, it is a
 * browsing history. `/discover?q=…` is worse: the query string is whatever
 * somebody typed.
 *
 * **So a pathname is never sent. A pattern from the list below is.** The
 * function is total and its range is that list plus one sentinel: there is no
 * input, malformed or hostile, for which it returns something derived from its
 * argument. That is the property `route-pattern.test.ts` exists to hold, and it
 * is why the matcher is a whitelist walk rather than a regular expression that
 * replaces the parts that look like identifiers — the second approach reports
 * the raw path whenever the replacement misses, which is exactly the case
 * nobody thought of.
 *
 * Query strings and fragments are not stripped here because they are never
 * read: the caller passes `location.pathname`, and anything after `?` or `#` is
 * refused rather than removed, so a caller that passed the whole URL by mistake
 * gets the sentinel instead of a leak.
 */

/**
 * What a path this build does not recognise is reported as.
 *
 * It covers a 404 as well, and that is not a gap: a 404's pathname is the one
 * the visitor asked for, so `/_not-found` — which is what the bundle budget
 * calls the same page — cannot be distinguished from a mistyped campaign URL
 * without reading the path, and reading the path is the thing this module
 * refuses to do.
 */
export const UNRECOGNISED_ROUTE = '(unrecognised)';

/**
 * Every route this application serves, as Next's own patterns.
 *
 * The list mirrors `apps/web/performance/budgets.json`, which is where the same
 * route names are already written down for the lab half of the measurement, so
 * that a field p75 and a First Load JS ceiling can be read against each other
 * without translating between two vocabularies. `route-pattern.test.ts` asserts
 * every entry below is a route that file also knows, so a pattern cannot be
 * invented here.
 *
 * A new route added to the application and not added here reports as
 * {@link UNRECOGNISED_ROUTE}. That is a deliberate failure mode: unmeasured is
 * visible in the summary, whereas a fallback that reported the raw path would
 * be a leak nobody would notice.
 */
export const ROUTE_PATTERNS: readonly string[] = [
  '/discover',
  '/projects/new',
  '/projects/[id]/back',
  '/projects/[id]/edit',
  '/projects/[id]/edit/basics',
  '/projects/[id]/edit/prelaunch',
  '/projects/[id]/edit/review',
  '/projects/[id]/edit/rewards',
  '/projects/[id]/edit/story',
  '/projects/[id]/prelaunch',
  '/settings/sessions',
];

/** Whether a string is one of the patterns this module may emit. */
export function isKnownRoutePattern(candidate: string): boolean {
  return candidate === UNRECOGNISED_ROUTE || ROUTE_PATTERNS.includes(candidate);
}

/**
 * The pattern a pathname belongs to, or {@link UNRECOGNISED_ROUTE}.
 *
 * Matching is segment by segment against a fixed list. A `[…]` segment matches
 * exactly one non-empty segment and its contents are discarded unread — the
 * campaign identifier is not hashed, truncated, or bucketed, because a hash of
 * an identifier is still a stable per-campaign key and joins back to the
 * campaign the moment somebody hashes the identifier list.
 */
export function routePatternOf(pathname: string): string {
  if (typeof pathname !== 'string') return UNRECOGNISED_ROUTE;
  // A pathname carrying either of these is not a pathname. Refused, not cleaned:
  // see the file comment.
  if (pathname.includes('?') || pathname.includes('#')) return UNRECOGNISED_ROUTE;

  const segments = split(pathname);
  for (const pattern of ROUTE_PATTERNS) {
    if (matches(split(pattern), segments)) return pattern;
  }
  return UNRECOGNISED_ROUTE;
}

/** `/a/b/` and `/a/b` are the same route; an empty segment is never one. */
function split(path: string): string[] {
  return path.split('/').filter((segment) => segment !== '');
}

function matches(pattern: string[], actual: string[]): boolean {
  if (pattern.length !== actual.length) return false;
  return pattern.every((segment, index) => {
    const candidate = actual[index];
    if (candidate === undefined) return false;
    return segment.startsWith('[') && segment.endsWith(']') ? true : segment === candidate;
  });
}
