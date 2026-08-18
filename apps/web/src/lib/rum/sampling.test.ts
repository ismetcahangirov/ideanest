import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SAMPLE_RATE,
  SESSION_STORAGE_KEY,
  hashToUnitInterval,
  isSampled,
  parseSampleRate,
  resolveSession,
} from './sampling';
import { isSessionId, isTraceId } from './correlation';

/** A `sessionStorage` stand-in, plus the two that misbehave in real browsers. */
function memoryStorage(initial: Record<string, string> = {}) {
  const entries = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => void entries.set(key, value),
    read: (key: string) => entries.get(key) ?? null,
  };
}

const throwingStorage = {
  getItem: (): string => {
    throw new DOMException('denied', 'SecurityError');
  },
  setItem: (): void => {
    throw new DOMException('denied', 'SecurityError');
  },
};

/** A v4-shaped identifier that is predictable, so a test can compare two. */
let counter = 0;
const uuid = (): string =>
  `${(counter += 1).toString(16).padStart(8, '0')}-0000-4000-8000-000000000000`;

describe('parseSampleRate', () => {
  it('defaults when unset or unreadable, rather than throwing', () => {
    expect(parseSampleRate(undefined)).toBe(DEFAULT_SAMPLE_RATE);
    expect(parseSampleRate('')).toBe(DEFAULT_SAMPLE_RATE);
    expect(parseSampleRate('   ')).toBe(DEFAULT_SAMPLE_RATE);
    expect(parseSampleRate('half')).toBe(DEFAULT_SAMPLE_RATE);
    expect(parseSampleRate('NaN')).toBe(DEFAULT_SAMPLE_RATE);
  });

  it('clamps to [0, 1]', () => {
    expect(parseSampleRate('-1')).toBe(0);
    expect(parseSampleRate('0')).toBe(0);
    expect(parseSampleRate('0.05')).toBe(0.05);
    expect(parseSampleRate('1')).toBe(1);
    expect(parseSampleRate('7')).toBe(1);
  });

  it('defaults to measuring every session', () => {
    // See the module comment: below a few hundred samples a p75 moves by more
    // between two ordinary days than a regression would move it, and this
    // platform has no production traffic to sample down from.
    expect(DEFAULT_SAMPLE_RATE).toBe(1);
  });
});

describe('isSampled', () => {
  /*
   * The property the whole percentile rests on. A coin flip per metric would
   * rank metrics rather than sessions, and the slow visits — which emit more
   * samples because they are slow — would be over-represented in exactly the
   * metric they are worst at.
   */
  it('gives the same answer for the same session, every time', () => {
    const id = '0f4b7a2c-1d3e-4f5a-8b9c-0d1e2f3a4b5c';
    const first = isSampled(id, 0.5);
    for (let attempt = 0; attempt < 100; attempt += 1) {
      expect(isSampled(id, 0.5)).toBe(first);
    }
  });

  it('is a pure function of the identifier and the rate', () => {
    expect(hashToUnitInterval('a')).toBe(hashToUnitInterval('a'));
    expect(hashToUnitInterval('a')).not.toBe(hashToUnitInterval('b'));
  });

  it('spreads identifiers roughly evenly across the interval', () => {
    const ids = Array.from({ length: 20_000 }, (_, index) => `session-${index}`);
    const sampled = ids.filter((id) => isSampled(id, 0.25)).length / ids.length;
    // Not an assertion about randomness — an assertion that the hash does not
    // collapse, which is what a 32-bit multiply done with `*` would do.
    expect(sampled).toBeGreaterThan(0.22);
    expect(sampled).toBeLessThan(0.28);
  });

  it('produces a value in [0, 1) for anything', () => {
    for (const seed of ['', 'a', 'ə', '🙂', 'x'.repeat(1000)]) {
      const value = hashToUnitInterval(seed);
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThan(1);
    }
  });

  it('short-circuits the extremes without hashing', () => {
    expect(isSampled('anything', 0)).toBe(false);
    expect(isSampled('anything', -1)).toBe(false);
    expect(isSampled('anything', 1)).toBe(true);
    expect(isSampled('anything', 2)).toBe(true);
  });
});

describe('resolveSession', () => {
  it('mints a session, stores it, and reuses it on the next read', () => {
    const storage = memoryStorage();

    const first = resolveSession(storage, 1, uuid);
    expect(isSessionId(first.id)).toBe(true);
    expect(isTraceId(first.traceId)).toBe(true);
    expect(first.sampled).toBe(true);

    const second = resolveSession(storage, 1, uuid);
    expect(second).toEqual(first);
    expect(storage.read(SESSION_STORAGE_KEY)).toContain(first.id);
  });

  /*
   * A reload is a continuation of the same visit. Re-deciding on it would be the
   * per-metric coin flip in slower motion.
   */
  it('keeps the stored decision even when the rate has since changed', () => {
    const storage = memoryStorage();
    const original = resolveSession(storage, 1, uuid);

    const afterRateChange = resolveSession(storage, 0.0001, uuid);

    expect(afterRateChange).toEqual(original);
    expect(afterRateChange.sampled).toBe(true);
  });

  it('replaces a stored record that is not the shape it wrote', () => {
    for (const stored of [
      'not json',
      '{}',
      '[]',
      'null',
      JSON.stringify({ id: 'chosen-by-somebody', sampled: true, traceId: 'a'.repeat(32) }),
      JSON.stringify({ id: '0f4b7a2c-1d3e-4f5a-8b9c-0d1e2f3a4b5c', sampled: 'yes' }),
      JSON.stringify({
        id: '0f4b7a2c-1d3e-4f5a-8b9c-0d1e2f3a4b5c',
        sampled: true,
        traceId: 'short',
      }),
    ]) {
      const storage = memoryStorage({ [SESSION_STORAGE_KEY]: stored });
      const session = resolveSession(storage, 1, uuid);
      expect(isSessionId(session.id)).toBe(true);
      expect(isTraceId(session.traceId)).toBe(true);
    }
  });

  /*
   * Safari in a private window, a strict cookie policy, a quota. All three throw
   * on the property access rather than returning null, and none of them is a
   * reason to throw before hydration.
   */
  it('works when storage is absent or throws', () => {
    expect(() => resolveSession(null, 1, uuid)).not.toThrow();
    expect(() => resolveSession(throwingStorage, 1, uuid)).not.toThrow();
    expect(isSessionId(resolveSession(throwingStorage, 1, uuid).id)).toBe(true);
  });
});
