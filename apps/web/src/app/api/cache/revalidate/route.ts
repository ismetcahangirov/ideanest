import { revalidateTag } from 'next/cache';
import { handleRevalidate } from '../../../../lib/cache/endpoint';

/**
 * The invalidation endpoint's URL. Everything it does is in `lib/cache/endpoint.ts` — this
 * file supplies the two things that only exist inside Next: the tag store and the
 * environment.
 *
 * `force-dynamic`, for `api/rum/route.ts`'s reason: a route that mutates a cache must run per
 * request, and without it a build-time evaluation is plausible enough to happen.
 *
 * `nodejs` and not `edge`, because `revalidateTag` writes to the same cache the server
 * renders read, and that is the runtime holding it.
 */
export const dynamic = 'force-dynamic';

export const runtime = 'nodejs';

export async function POST(request: Request): Promise<Response> {
  const outcome = await handleRevalidate(request, {
    /*
     * `'max'` is Next 16's own answer for a route handler. Calling `revalidateTag` with one
     * argument is deprecated and warns; `updateTag`, the immediate-expiry version, refuses
     * to run outside a Server Action and says so by name. `'max'` expires every entry
     * carrying the tag, which is exactly what the service is asking for.
     */
    revalidate: (tag) => revalidateTag(tag, 'max'),
    /*
     * Read per request rather than captured at module scope. `lib/api/server.ts` states the
     * same rule for `apiOrigin`: a module constant captures the value at evaluation, which
     * during a Next build is before the deployment's environment exists.
     */
    secret: process.env['IDEANEST_REVALIDATE_SECRET'],
  });

  return Response.json(outcome.body, {
    status: outcome.status,
    // Nothing about this response may be held anywhere, by anything.
    headers: { 'cache-control': 'no-store' },
  });
}
