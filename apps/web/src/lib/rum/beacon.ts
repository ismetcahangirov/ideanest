import { REQUEST_ID_HEADER, TRACEPARENT_HEADER, traceparentOf } from './correlation';
import type { RumPayload } from './payload';

/**
 * Getting the measurement off the page without costing the page anything.
 *
 * <h2>Why `sendBeacon` and not `fetch`</h2>
 *
 * The metrics that matter arrive last. LCP is not final until the user
 * interacts or the page is hidden; INP is not final until they have stopped
 * interacting; CLS accumulates for the whole visit. So the moment a beacon is
 * worth sending is the moment the page is going away, and that is precisely when
 * a browser cancels in-flight requests. `navigator.sendBeacon` exists for this:
 * the request is handed to the user agent, which keeps it alive after the
 * document is gone, and it returns synchronously so nothing blocks the unload.
 *
 * <h2>The fallback, and when it runs</h2>
 *
 * `sendBeacon` is not always available and does not always accept. It is absent
 * in a few embedded browsers; it returns `false` when the user agent's queue is
 * full or the body exceeds its own limit; and some privacy extensions remove it.
 * `fetch` with `keepalive: true` is the documented equivalent — same
 * after-unload guarantee, a 64 kB body limit shared across in-flight keepalive
 * requests, and it can set headers, which `sendBeacon` cannot. So the fallback
 * additionally sends `X-Request-Id` and `traceparent`, and the endpoint prefers
 * them when they are there. A `fetch` that rejects is swallowed: a monitoring
 * feature that throws into the application is a monitoring feature that has
 * become the incident.
 *
 * <h2>What this must not cost</h2>
 *
 * This is a performance feature and it must not be measurable in the thing it
 * measures. Reporting a metric does no work beyond pushing an object onto an
 * array. Serialising and sending happens either in an idle callback, or
 * synchronously during `pagehide` — and it has to be synchronous there, because
 * an idle callback scheduled while the page is unloading never runs and the
 * whole visit is lost. Nothing here touches the DOM, reads layout, or allocates
 * per interaction.
 */

/** Where a beacon goes. Same origin, so no preflight and no third party. */
export const RUM_ENDPOINT = '/api/rum';

/** The `Content-Type` a beacon announces. `sendBeacon` takes it from the `Blob`. */
export const BEACON_CONTENT_TYPE = 'application/json';

/** How the beacon left, for the reporter's own tests and for nothing else. */
export type DeliveryMethod = 'sendBeacon' | 'fetch' | 'none';

export interface BeaconEnvironment {
  readonly sendBeacon?: ((url: string, data: BodyInit) => boolean) | undefined;
  readonly fetch?: typeof fetch | undefined;
}

/**
 * Hands one serialised payload to the browser, and reports which door it used.
 *
 * The order is `sendBeacon`, then `fetch`, then give up. Giving up is silent on
 * purpose: there is no user-visible consequence of a lost measurement, and the
 * only thing a console warning would reliably do is appear in the console of a
 * visitor who had installed something to prevent exactly this.
 */
export function deliver(
  body: string,
  environment: BeaconEnvironment,
  correlation: { readonly requestId: string; readonly traceId: string; readonly spanId: string },
  url: string = RUM_ENDPOINT,
): DeliveryMethod {
  const { sendBeacon, fetch: fetchImpl } = environment;

  if (typeof sendBeacon === 'function') {
    try {
      // A `Blob` and not the bare string: `sendBeacon` sends a string as
      // `text/plain;charset=UTF-8`, and the endpoint refuses that media type.
      if (sendBeacon(url, new Blob([body], { type: BEACON_CONTENT_TYPE }))) return 'sendBeacon';
    } catch {
      // Some extensions replace `sendBeacon` with something that throws. That is
      // a reason to try the other door, not to stop.
    }
  }

  if (typeof fetchImpl === 'function') {
    try {
      void fetchImpl(url, {
        method: 'POST',
        body,
        // The whole point: the request outlives the document.
        keepalive: true,
        headers: {
          'content-type': BEACON_CONTENT_TYPE,
          [REQUEST_ID_HEADER]: correlation.requestId,
          [TRACEPARENT_HEADER]: traceparentOf(correlation.traceId, correlation.spanId),
        },
        credentials: 'same-origin',
        cache: 'no-store',
      }).catch(() => {
        // See the file comment: a lost sample is not an error worth raising into
        // the page that was being measured.
      });
      return 'fetch';
    } catch {
      return 'none';
    }
  }

  return 'none';
}

/** The bytes a payload will occupy, so the caller can honour `MAX_BODY_BYTES`. */
export function serialise(payload: RumPayload): string {
  return JSON.stringify(payload);
}
