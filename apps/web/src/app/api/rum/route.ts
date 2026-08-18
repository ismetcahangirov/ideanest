import { defaultDependencies, handleRumGet, handleRumPost } from '../../../lib/rum/endpoint';
import { newSalt } from '../../../lib/rum/limits';
import { LocalSink, localSinkEnabled } from '../../../lib/rum/sink';

/**
 * The collection endpoint. Everything it does is in `lib/rum/endpoint.ts`; this
 * file exists to give that function a URL and one process-lifetime state.
 *
 * <h2>Why the state is a module variable</h2>
 *
 * The rate limiter's counters and the salt they are keyed with have to outlive a
 * request and must not outlive the process. A module variable in a route file is
 * exactly that, and it is the same arrangement `lib/api/access-token.ts` uses on
 * the client for the same reason. It is also why `limits.ts` says plainly that
 * two replicas enforce two separate limits: this is per process, and there is no
 * pretending otherwise.
 *
 * `force-dynamic`, because a route that writes to a log must run per request.
 * Without it a build-time evaluation would be plausible enough to happen and the
 * endpoint would answer from a cache.
 */
export const dynamic = 'force-dynamic';

/**
 * `nodejs` and not `edge`. `console.log` on the edge runtime goes to a per-region
 * log stream, and the sink's whole design is one line beside the application's
 * other lines.
 */
export const runtime = 'nodejs';

const sink = localSinkEnabled(process.env) ? new LocalSink() : null;
const dependencies = defaultDependencies({ salt: newSalt(), sink });

export function POST(request: Request): Promise<Response> {
  return handleRumPost(request, dependencies);
}

export function GET(): Response {
  return handleRumGet(dependencies);
}
