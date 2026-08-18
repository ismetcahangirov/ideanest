import { describe, expect, it } from 'vitest';
import { canOptimise, intrinsicSize } from './source';

/**
 * The guard in front of `next/image`.
 *
 * The failure it prevents is not a slow image, it is a blank page: an address
 * no `remotePatterns` entry matches makes `next/image` throw, and a throw in a
 * server component takes the route with it. One creator pasting `http://` must
 * not be able to empty the discovery feed.
 */

describe('canOptimise', () => {
  it('accepts an HTTPS address', () => {
    expect(canOptimise('https://images.example.test/cover.jpg')).toBe(true);
    expect(canOptimise('https://images.example.test/a%20b.jpg?v=2')).toBe(true);
  });

  it.each([
    ['plain HTTP', 'http://images.example.test/cover.jpg'],
    ['a data URI', 'data:image/png;base64,iVBORw0KGgo='],
    ['a relative path', '/covers/cover.jpg'],
    ['a protocol-relative address', '//images.example.test/cover.jpg'],
    ['a javascript URL', 'javascript:alert(1)'],
    ['prose', 'my cover photo'],
    ['nothing', ''],
  ])('refuses %s', (_label, value) => {
    expect(canOptimise(value)).toBe(false);
  });
});

describe('intrinsicSize', () => {
  it('passes a real measurement through', () => {
    expect(intrinsicSize({ width: 1600, height: 900 })).toEqual({ width: 1600, height: 900 });
  });

  it.each([
    ['a failed measurement', { width: 0, height: 0 }],
    ['half a measurement', { width: 1600, height: 0 }],
    ['a negative', { width: -1600, height: 900 }],
    ['not a number', { width: Number.NaN, height: 900 }],
  ])('reports %s as unusable rather than guessing', (_label, size) => {
    // A call site that knows the shape is unknown can choose the crop token,
    // which is honest. Reserving `1600 / 0` would reserve nothing at all.
    expect(intrinsicSize(size)).toBeNull();
  });
});
