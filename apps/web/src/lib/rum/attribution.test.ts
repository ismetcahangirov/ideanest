import { describe, expect, it } from 'vitest';
import {
  CONNECTION_CLASSES,
  DEVICE_CLASSES,
  NAVIGATION_TYPES,
  connectionClassOf,
  deviceClassOf,
  navigationTypeOf,
} from './attribution';

describe('connectionClassOf', () => {
  it('reads the four values the Network Information API defines', () => {
    for (const effectiveType of ['slow-2g', '2g', '3g', '4g']) {
      expect(connectionClassOf({ connection: { effectiveType } })).toBe(effectiveType);
    }
  });

  /*
   * Safari and Firefox implement none of this, which is most of the phones in
   * this market. `unknown` is a real answer and not a failure.
   */
  it('answers unknown where the API is absent', () => {
    expect(connectionClassOf({})).toBe('unknown');
    expect(connectionClassOf({ connection: {} })).toBe('unknown');
    expect(connectionClassOf(null)).toBe('unknown');
    expect(connectionClassOf(undefined)).toBe('unknown');
    expect(connectionClassOf('navigator')).toBe('unknown');
  });

  /*
   * A browser inventing a value would otherwise write a new string into the log
   * — which is exactly the free-text field the whole payload is designed not to
   * have.
   */
  it('widens the unknown bucket rather than passing a new value through', () => {
    expect(connectionClassOf({ connection: { effectiveType: '5g' } })).toBe('unknown');
    expect(connectionClassOf({ connection: { effectiveType: 42 } })).toBe('unknown');
    expect(connectionClassOf({ connection: { effectiveType: 'aygun@example.az' } })).toBe('unknown');
  });
});

describe('deviceClassOf', () => {
  it.each([
    [320, 'mobile'],
    [767, 'mobile'],
    [768, 'tablet'],
    [1023, 'tablet'],
    [1024, 'desktop'],
    [2560, 'desktop'],
  ] as const)('buckets a %s px viewport as %s', (width, expected) => {
    expect(deviceClassOf(width)).toBe(expected);
  });

  it('answers unknown for a width that is not one', () => {
    expect(deviceClassOf(0)).toBe('unknown');
    expect(deviceClassOf(-1)).toBe('unknown');
    expect(deviceClassOf(Number.NaN)).toBe('unknown');
    expect(deviceClassOf(Number.POSITIVE_INFINITY)).toBe('unknown');
  });
});

describe('navigationTypeOf', () => {
  it('accepts the seven and nothing else', () => {
    for (const type of NAVIGATION_TYPES) {
      expect(navigationTypeOf(type)).toBe(type);
    }
    expect(navigationTypeOf('back_forward')).toBe('unknown');
    expect(navigationTypeOf(undefined)).toBe('unknown');
    expect(navigationTypeOf(7)).toBe('unknown');
  });
});

describe('the vocabularies', () => {
  /*
   * Fewer than four hundred combinations exist across the four dimensions, which
   * is what keeps the attribution from being a fingerprint. This is the arithmetic
   * written down so that adding a high-cardinality dimension fails a test.
   */
  it('stay small enough not to identify anybody', () => {
    const combinations =
      CONNECTION_CLASSES.length * DEVICE_CLASSES.length * NAVIGATION_TYPES.length;
    expect(combinations).toBeLessThan(400);
  });
});
