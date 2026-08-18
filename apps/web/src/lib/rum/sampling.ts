import { isSessionId, isTraceId, newTraceId } from './correlation';

/**
 * Who is measured, decided once per session rather than once per metric.
 *
 * <h2>Why the decision is per session and not per sample</h2>
 *
 * A coin flip per metric looks equivalent and destroys the thing being measured.
 * Core Web Vitals is a **p75 over sessions**: the question is "three quarters of
 * visits were at least this good", and it is answered by ranking whole visits.
 * Flipping per metric ranks *metrics*, so a session that reported LCP but was
 * dropped for INP appears in one distribution and not the other, and the slow
 * sessions — the ones that emit more samples because they are slow enough for a
 * user to interact repeatedly — are over-represented in exactly the metric they
 * are worst at. The number that comes out has the shape of a p75 and is not one.
 *
 * The decision is therefore a pure function of the session identifier, taken
 * once, cached in `sessionStorage`, and reused for every metric that session
 * produces — including after a reload, which is a continuation of the same visit
 * and would otherwise be a fresh flip.
 *
 * `sessionStorage` and not `localStorage`: it dies with the tab, so nothing here
 * outlives the visit or follows a person between visits. It is also not a cookie
 * and is never sent to a server, so there is nothing to consent to.
 *
 * <h2>Why the default rate is 1.0</h2>
 *
 * Because a percentile computed from too few samples is a number that will be
 * believed and should not be. The working rule is **roughly a thousand samples
 * per route per day** before a p75 is stable enough to notice a regression in;
 * below a few hundred it moves by more between two ordinary days than a real
 * regression would move it — the same argument
 * `apps/web/performance/README.md` makes for not gating on lab Core Web Vitals.
 *
 * `docs/architecture.md` §11.4 states the platform's current position plainly:
 * it has "no metrics, tracing, or alerting", and there is no production traffic
 * to sample down from. At this volume any rate below 1.0 buys nothing and costs
 * the ability to say anything. The rate exists as a knob because the day
 * `/discover` is serving real traffic the arithmetic reverses, and the rule for
 * turning it down is written above: keep about a thousand samples per route per
 * day, and no fewer.
 *
 * `0` disables collection entirely, and is the correct setting for an
 * environment where the endpoint has nowhere to write.
 */

/**
 * The rate, as a fraction between 0 and 1.
 *
 * `NEXT_PUBLIC_` because the decision is taken in the browser. It is inlined at
 * build time, so changing it means rebuilding — which is the honest description
 * of a value baked into a bundle, and the reason the reader below is given the
 * raw string rather than a `process.env` lookup it could not have inlined.
 */
export const SAMPLE_RATE_VARIABLE = 'NEXT_PUBLIC_IDEANEST_RUM_SAMPLE_RATE';

export const DEFAULT_SAMPLE_RATE = 1;

/** Where the session's identifier and its decision are kept, for the tab's life. */
export const SESSION_STORAGE_KEY = 'ideanest.rum.session';

export interface RumSession {
  /** A v4 UUID. Not an account, not a device, and gone when the tab closes. */
  readonly id: string;
  /** Whether this visit reports. Taken once; every metric obeys it. */
  readonly sampled: boolean;
  /** The trace every beacon from this session belongs to (§18.1). */
  readonly traceId: string;
}

/**
 * A configured rate, clamped, or the default.
 *
 * A value that is set but unreadable falls back rather than throwing, which is
 * the opposite of `lib/seo/sitemap/config.ts`'s rule for `IDEANEST_SITE_URL` and
 * is the right way round for this one: a bad site URL silently poisons every
 * canonical in production, whereas a bad sample rate would take a monitoring
 * feature and turn it into a blank page.
 */
export function parseSampleRate(raw: string | undefined): number {
  if (raw === undefined) return DEFAULT_SAMPLE_RATE;
  const trimmed = raw.trim();
  if (trimmed === '') return DEFAULT_SAMPLE_RATE;

  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed)) return DEFAULT_SAMPLE_RATE;
  if (parsed <= 0) return 0;
  return parsed >= 1 ? 1 : parsed;
}

/**
 * FNV-1a, 32 bit, mapped onto `[0, 1)`.
 *
 * Deterministic and dependency-free. It is not a cryptographic hash and does not
 * need to be: the input is already 122 bits of `crypto.randomUUID()` entropy, so
 * the hash is only being asked to spread it evenly, and an attacker who wanted
 * to be sampled in could simply reload until they were.
 */
export function hashToUnitInterval(seed: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < seed.length; index += 1) {
    hash ^= seed.charCodeAt(index);
    // `Math.imul` keeps the multiply in 32 bits; `*` would lose the low bits to
    // the float mantissa and collapse the output range.
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0) / 0x1_0000_0000;
}

/** Whether this session reports, given the rate. Same session, same answer, always. */
export function isSampled(sessionId: string, rate: number): boolean {
  if (rate <= 0) return false;
  if (rate >= 1) return true;
  return hashToUnitInterval(sessionId) < rate;
}

interface StoredSession {
  id?: unknown;
  sampled?: unknown;
  traceId?: unknown;
}

/**
 * The session for this tab: the one already stored, or a new one stored now.
 *
 * A stored record that does not parse, or whose fields are not the shapes
 * `correlation.ts` accepts, is replaced rather than repaired. `sessionStorage`
 * is writable by any script on the page, and a session identifier chosen by
 * something else is either a bug or somebody selecting their own sampling
 * outcome; neither is worth carrying forward.
 *
 * Storage that throws — Safari in private browsing, a quota, a `SecurityError`
 * behind a strict cookie policy — is treated as absent. The session is then
 * minted per page load, which measures slightly fewer whole visits and is a long
 * way better than an exception thrown before hydration.
 */
export function resolveSession(
  storage: Pick<Storage, 'getItem' | 'setItem'> | null,
  rate: number,
  mintId: () => string = () => crypto.randomUUID(),
): RumSession {
  const stored = read(storage);
  if (stored !== null) return stored;

  const id = mintId();
  const session: RumSession = { id, sampled: isSampled(id, rate), traceId: newTraceId() };
  try {
    storage?.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  } catch {
    // See above: an unwritable storage costs a little accuracy and nothing else.
  }
  return session;
}

function read(storage: Pick<Storage, 'getItem'> | null): RumSession | null {
  if (storage === null) return null;

  let raw: string | null;
  try {
    raw = storage.getItem(SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
  if (raw === null) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;

  const { id, sampled, traceId } = parsed as StoredSession;
  if (typeof id !== 'string' || !isSessionId(id)) return null;
  if (typeof sampled !== 'boolean') return null;
  if (typeof traceId !== 'string' || !isTraceId(traceId)) return null;

  return { id, sampled, traceId };
}
