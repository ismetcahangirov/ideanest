/**
 * The rule `instrumentation-client.ts` applies to every outgoing `fetch`, kept
 * here so it can be tested without a `window` and without patching a global.
 *
 * Wrapping `window.fetch` is a heavy-handed thing to do and the reasoning for it
 * is in that file. What lives here is the part that has to be exactly right:
 * **which requests are touched, and what happens to the ones that are not.**
 */

/** Only the proxied service. Nothing else on this origin wants a trace header. */
const API_PATH_PREFIX = '/v1/';

type FetchArguments = [input: RequestInfo | URL, init?: RequestInit];

/**
 * The arguments a wrapped `fetch` should pass on.
 *
 * Returns its input unchanged for anything that is not a same-origin `/v1`
 * request, for anything whose URL will not parse, and for anything that already
 * carries the headers. The one case it modifies is the one the application
 * actually makes: a relative path string and an optional init.
 *
 * A `Request` object is handled by rebuilding it, which is the only way to add a
 * header to one — its `headers` are immutable once constructed for a request
 * with the `request` guard. If the rebuild throws, the original is returned:
 * a lost trace header is a nuisance and a lost pledge is not.
 */
export function propagateCorrelation(
  input: RequestInfo | URL,
  init: RequestInit | undefined,
  origin: string,
  headersToAdd: () => Record<string, string>,
): FetchArguments {
  let url: URL;
  try {
    url = new URL(input instanceof Request ? input.url : String(input), origin);
  } catch {
    return [input, init];
  }

  if (url.origin !== origin) return [input, init];
  if (!url.pathname.startsWith(API_PATH_PREFIX)) return [input, init];

  const additions = headersToAdd();

  if (input instanceof Request) {
    try {
      const headers = new Headers(input.headers);
      if (!addMissing(headers, additions)) return [input, init];
      return [new Request(input, { headers }), init];
    } catch {
      return [input, init];
    }
  }

  const headers = new Headers(init?.headers);
  if (!addMissing(headers, additions)) return [input, init];
  return [input, { ...init, headers }];
}

/**
 * Adds only the headers that are not already set, and reports whether anything
 * changed.
 *
 * "Only what is missing" is what lets a future `lib/api/client.ts` take this job
 * over without a conflict: the moment it sets `traceparent` itself, this stops
 * setting it. "Whether anything changed" is what avoids rebuilding a `Request`
 * for nothing.
 */
function addMissing(headers: Headers, additions: Record<string, string>): boolean {
  let changed = false;
  for (const [name, value] of Object.entries(additions)) {
    if (headers.has(name)) continue;
    headers.set(name, value);
    changed = true;
  }
  return changed;
}
