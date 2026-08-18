import {
  isConnectionClass,
  isDeviceClass,
  isNavigationType,
  type ConnectionClass,
  type DeviceClass,
  type NavigationType,
} from './attribution';
import { acceptableIdentifier, isSessionId, isSpanId, isTraceId } from './correlation';
import { isFieldMetricName, isPlausibleValue, type FieldMetricName } from './metrics';
import { carriesIdentifyingData } from './redaction';
import { isKnownRoutePattern } from './route-pattern';

/**
 * What a beacon is allowed to be, and the parser that is the endpoint's only
 * door.
 *
 * The schema is the privacy policy in executable form. **Every string field is
 * either a member of a closed vocabulary or matches a strict machine pattern,
 * and there is no field that accepts text.** Adding one would be a change to
 * this file, reviewed as such — which is the whole reason the shape lives here
 * rather than being implied by whatever the reporter happens to send.
 *
 * <h2>Unknown keys are refused, not ignored</h2>
 *
 * The usual instinct is to ignore what you do not recognise, for
 * forward-compatibility. That is the wrong trade for a public unauthenticated
 * write whose output goes into a log: the only client is this application's own
 * reporter, deployed from the same commit, so there is no version skew to absorb
 * — and an ignored key is a key that arrives, gets parsed, and is one refactor
 * away from being logged. Refusing means a payload can never contain a field
 * nobody reviewed.
 *
 * <h2>What the client is not trusted with</h2>
 *
 * The rating (`good` / `needs-improvement` / `poor`) is **not** on the wire. The
 * endpoint derives it from the value with `metrics.ts`, so the thresholds have
 * one home and a forged payload cannot claim a poor value is good. The
 * timestamp is not on the wire either: the endpoint stamps arrival, because a
 * clock the sender controls is a clock that can place a sample outside the
 * window it belongs to.
 */

/** The schema version. One field, so that a future change is negotiable. */
export const PAYLOAD_VERSION = 1;

/**
 * Bytes. A refusal above this is a `413`.
 *
 * The largest legitimate beacon is the twenty-sample cap below, which measures
 * about 1.4 kB. Eight kibibytes leaves room for the shape to grow and is well
 * under the 64 kB `navigator.sendBeacon` is specified to accept, so a body that
 * reaches this endpoint over that size did not come from this application.
 */
export const MAX_BODY_BYTES = 8 * 1024;

/**
 * Samples per beacon.
 *
 * Six metrics exist and one page load produces at most one of each, so a
 * legitimate beacon carries between one and six. Twenty leaves room for a
 * client-side route change to add a second round before the buffer flushes,
 * without letting one request write an unbounded number of rows.
 */
export const MAX_SAMPLES = 20;

export interface RumSample {
  readonly name: FieldMetricName;
  readonly value: number;
  readonly navigationType: NavigationType;
}

export interface RumPayload {
  readonly v: typeof PAYLOAD_VERSION;
  /** §18.1's `requestId`, for this beacon. */
  readonly requestId: string;
  /** §18.1's `traceId`, for the whole session. */
  readonly traceId: string;
  /** §18.1's `spanId`, for this beacon. */
  readonly spanId: string;
  /** A v4 UUID that dies with the tab. Not an account and not a device. */
  readonly sessionId: string;
  /** A Next route pattern, never a URL. See `route-pattern.ts`. */
  readonly route: string;
  readonly connection: ConnectionClass;
  readonly device: DeviceClass;
  readonly samples: readonly RumSample[];
}

const PAYLOAD_KEYS = [
  'v',
  'requestId',
  'traceId',
  'spanId',
  'sessionId',
  'route',
  'connection',
  'device',
  'samples',
] as const;

const SAMPLE_KEYS = ['name', 'value', 'navigationType'] as const;

/**
 * Why a payload was refused.
 *
 * A closed set, because the reason is returned to an anonymous caller and
 * echoing what was wrong with their input is how a validator becomes an oracle.
 * None of these names a value.
 */
export type RejectionReason =
  | 'not-json'
  | 'not-an-object'
  | 'unknown-field'
  | 'unsupported-version'
  | 'malformed-field'
  | 'no-samples'
  | 'too-many-samples'
  | 'implausible-value'
  | 'identifying-data';

export type ParseResult =
  | { readonly ok: true; readonly payload: RumPayload }
  | { readonly ok: false; readonly reason: RejectionReason };

function reject(reason: RejectionReason): ParseResult {
  return { ok: false, reason };
}

/**
 * A payload, or the reason it is not one.
 *
 * Total: it throws for no input. A validator that can throw on a public
 * unauthenticated endpoint is a validator whose exception path is the attack.
 */
export function parseRumPayload(text: string): ParseResult {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return reject('not-json');
  }

  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return reject('not-an-object');
  }

  const record = parsed as Record<string, unknown>;
  if (!onlyKeys(record, PAYLOAD_KEYS)) return reject('unknown-field');
  if (record['v'] !== PAYLOAD_VERSION) return reject('unsupported-version');

  const requestId = acceptableIdentifier(asString(record['requestId']));
  if (requestId === null) return reject('malformed-field');

  const traceId = asString(record['traceId']);
  if (traceId === null || !isTraceId(traceId)) return reject('malformed-field');

  const spanId = asString(record['spanId']);
  if (spanId === null || !isSpanId(spanId)) return reject('malformed-field');

  const sessionId = asString(record['sessionId']);
  if (sessionId === null || !isSessionId(sessionId)) return reject('malformed-field');

  const route = asString(record['route']);
  if (route === null || !isKnownRoutePattern(route)) return reject('malformed-field');

  const connection = asString(record['connection']);
  if (connection === null || !isConnectionClass(connection)) return reject('malformed-field');

  const device = asString(record['device']);
  if (device === null || !isDeviceClass(device)) return reject('malformed-field');

  const rawSamples = record['samples'];
  if (!Array.isArray(rawSamples)) return reject('malformed-field');
  if (rawSamples.length === 0) return reject('no-samples');
  if (rawSamples.length > MAX_SAMPLES) return reject('too-many-samples');

  const samples: RumSample[] = [];
  for (const raw of rawSamples) {
    const sample = parseSample(raw);
    if (!('ok' in sample)) return sample.failure;
    samples.push(sample.ok);
  }

  const payload: RumPayload = {
    v: PAYLOAD_VERSION,
    requestId,
    traceId,
    spanId,
    sessionId,
    route,
    connection,
    device,
    samples,
  };

  /*
   * The second lock, over a payload that has already passed every rule above.
   * `redaction.ts` explains why a schema that forbids everything is checked
   * again anyway, and why the four correlation identifiers are the one thing it
   * is not run over: each has already been proved to be machine-minted hex or a
   * v4 UUID, and a random hex identifier occasionally coincides with a
   * Luhn-valid card number.
   */
  const { requestId: _r, traceId: _t, spanId: _s, sessionId: _i, ...checkable } = payload;
  if (carriesIdentifyingData(checkable)) return reject('identifying-data');

  return { ok: true, payload };
}

function parseSample(raw: unknown): { ok: RumSample } | { failure: ParseResult } {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    return { failure: reject('malformed-field') };
  }

  const record = raw as Record<string, unknown>;
  if (!onlyKeys(record, SAMPLE_KEYS)) return { failure: reject('unknown-field') };

  const name = asString(record['name']);
  if (name === null || !isFieldMetricName(name)) return { failure: reject('malformed-field') };

  const value = record['value'];
  if (typeof value !== 'number') return { failure: reject('malformed-field') };
  if (!isPlausibleValue(name, value)) return { failure: reject('implausible-value') };

  const navigationType = asString(record['navigationType']);
  if (navigationType === null || !isNavigationType(navigationType)) {
    return { failure: reject('malformed-field') };
  }

  return { ok: { name, value, navigationType } };
}

/**
 * A string, and never a `String` object or a number that would coerce.
 *
 * `JSON.parse` cannot produce either, but this function is also the door for
 * anything a future caller hands it, and `typeof` is the cheapest possible way
 * to keep that true.
 */
function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

/**
 * Whether an object has these keys and no others.
 *
 * `Object.keys` and not `in`, so a key inherited from a prototype cannot satisfy
 * a required field — `JSON.parse` never produces one, but the check is free.
 */
function onlyKeys(record: Record<string, unknown>, allowed: readonly string[]): boolean {
  const keys = Object.keys(record);
  return keys.length === allowed.length && keys.every((key) => allowed.includes(key));
}
