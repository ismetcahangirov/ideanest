/**
 * What a slow measurement is attributed to, and the reason each of these four
 * and nothing else.
 *
 * The test for a field is not "would it be interesting" — almost anything would
 * be — but **"could a team act on it, and could it name somebody"**. A route
 * says which page to open. A navigation type separates a cold load from a
 * back-forward-cache restore, which are different bugs with the same LCP. A
 * connection class separates a slow network from slow code. A device class
 * separates a slow phone from a slow server. Together they are four small closed
 * sets: fewer than four hundred distinct combinations exist, which is not a
 * fingerprint, and every one of them points at something to change.
 *
 * **Every value below is drawn from a fixed vocabulary.** There is no field on
 * the wire that a free string can enter — the same discipline
 * `az.ideanest.shared.observability.LogFields` applies on the service, where
 * there is a method per safe shape and none that takes text. A value outside its
 * vocabulary becomes `unknown` rather than being passed through, so a browser
 * inventing an `effectiveType` nobody has heard of widens the `unknown` bucket
 * instead of writing a new string into the log.
 *
 * What is deliberately **not** collected: the user agent string (high-entropy,
 * and the device question is answered by a viewport bucket), screen dimensions
 * (the same, at higher resolution), `deviceMemory` and `hardwareConcurrency`
 * (two of the strongest fingerprinting signals the platform exposes, for an
 * answer the connection and device classes already approximate), any account
 * identifier, and the IP address — which the endpoint sees and never stores; see
 * `limits.ts`.
 */

/**
 * The Network Information API's `effectiveType`, which is already a closed set
 * of four, plus the answer for every browser that does not implement it —
 * Safari and Firefox, which is most of the iPhones in this market.
 */
export const CONNECTION_CLASSES = ['slow-2g', '2g', '3g', '4g', 'unknown'] as const;
export type ConnectionClass = (typeof CONNECTION_CLASSES)[number];

/** Three buckets, because the layouts have three. */
export const DEVICE_CLASSES = ['mobile', 'tablet', 'desktop', 'unknown'] as const;
export type DeviceClass = (typeof DEVICE_CLASSES)[number];

/**
 * `PerformanceNavigationTiming.type`, normalised as Next documents it, plus
 * `back-forward-cache` for a bfcache restore and `restore` for a discarded tab.
 */
export const NAVIGATION_TYPES = [
  'navigate',
  'reload',
  'back-forward',
  'back-forward-cache',
  'prerender',
  'restore',
  'unknown',
] as const;
export type NavigationType = (typeof NAVIGATION_TYPES)[number];

/**
 * Tailwind's `md` and `lg`, in pixels — 48rem and 64rem at the root font size.
 *
 * The boundaries are the ones the layouts already change at rather than numbers
 * chosen here, so "mobile is slow" and "the mobile layout is slow" are the same
 * statement. `docs/ui-kit.md` names no breakpoint tokens; if it ever does, these
 * two move to it.
 */
const TABLET_FROM = 768;
const DESKTOP_FROM = 1024;

interface NetworkInformationLike {
  readonly effectiveType?: unknown;
}

/**
 * `navigator.connection` is not in the DOM lib and is absent in two of the three
 * engines, so it is read defensively rather than declared as if it were there.
 */
export function connectionClassOf(navigatorLike: unknown): ConnectionClass {
  if (typeof navigatorLike !== 'object' || navigatorLike === null) return 'unknown';
  const connection = (navigatorLike as { connection?: NetworkInformationLike }).connection;
  const effectiveType = connection?.effectiveType;
  if (typeof effectiveType !== 'string') return 'unknown';
  return (CONNECTION_CLASSES as readonly string[]).includes(effectiveType)
    ? (effectiveType as ConnectionClass)
    : 'unknown';
}

/**
 * The viewport width, bucketed. Not the screen width: a desktop browser in a
 * narrow window is running the mobile layout, and the mobile layout is the thing
 * being measured.
 */
export function deviceClassOf(viewportWidth: number): DeviceClass {
  if (!Number.isFinite(viewportWidth) || viewportWidth <= 0) return 'unknown';
  if (viewportWidth < TABLET_FROM) return 'mobile';
  return viewportWidth < DESKTOP_FROM ? 'tablet' : 'desktop';
}

/** The navigation type if it is one of the seven, otherwise `unknown`. */
export function navigationTypeOf(raw: unknown): NavigationType {
  if (typeof raw !== 'string') return 'unknown';
  return (NAVIGATION_TYPES as readonly string[]).includes(raw)
    ? (raw as NavigationType)
    : 'unknown';
}

export function isConnectionClass(candidate: string): candidate is ConnectionClass {
  return (CONNECTION_CLASSES as readonly string[]).includes(candidate);
}

export function isDeviceClass(candidate: string): candidate is DeviceClass {
  return (DEVICE_CLASSES as readonly string[]).includes(candidate);
}

export function isNavigationType(candidate: string): candidate is NavigationType {
  return (NAVIGATION_TYPES as readonly string[]).includes(candidate);
}
