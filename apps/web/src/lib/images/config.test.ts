import { describe, expect, it } from 'vitest';
// A JSDoc-typed `.mjs` module; `next.config.mjs` explains why it cannot be
// TypeScript in this repository.
import { images } from '../../../next.config.mjs';

/**
 * The delivery settings, asserted rather than trusted.
 *
 * Every one of these is invisible when it is wrong. A missing `formats` serves
 * the original JPEG to a browser that would have taken AVIF; a `deviceSizes`
 * that quietly reverted to the default keeps encoding 3840-pixel variants of
 * photographs nothing displays; a `remotePatterns` that stops matching throws
 * inside a server component and blanks the route. None of that shows up in a
 * screenshot, so it shows up here.
 */

const config = images as {
  formats: string[];
  deviceSizes: number[];
  imageSizes: number[];
  qualities: number[];
  remotePatterns: { protocol: string; hostname: string }[];
  minimumCacheTTL: number;
  dangerouslyAllowSVG: boolean;
};

describe('image delivery', () => {
  it('offers AVIF before WebP', () => {
    // The order is the negotiation order. docs/architecture.md §13.1.
    expect(config.formats).toEqual(['image/avif', 'image/webp']);
  });

  it('stops at the widest width any layout can use', () => {
    /*
     * 720 CSS pixels is the widest fixed box in the application (the prelaunch
     * cover), so 1440 covers it at 2x. A candidate above that is an AVIF encode
     * of a picture nobody can see. It is also §13.1's `hero` variant.
     */
    expect(Math.max(...config.deviceSizes)).toBe(1440);
    expect(config.deviceSizes).not.toContain(3840);
  });

  it('carries the thumbnail width the media pipeline will store', () => {
    // §13.1: thumbnail 160w. One ladder, not two that drift.
    expect(config.imageSizes).toContain(160);
  });

  it('keeps the candidate ladder ascending and free of duplicates', () => {
    for (const ladder of [config.deviceSizes, config.imageSizes]) {
      expect([...ladder].sort((a, b) => a - b)).toEqual(ladder);
      expect(new Set(ladder).size).toBe(ladder.length);
    }
  });

  it('declares exactly the qualities the application asks for', () => {
    // Next 16 rejects an undeclared quality outright, and nothing overrides 75.
    expect(config.qualities).toEqual([75]);
  });

  it('optimises HTTPS and nothing else', () => {
    // `src/lib/images/source.ts` keeps the same rule on the render side; the
    // two have to agree or a mismatch becomes a thrown render.
    expect(config.remotePatterns.every((pattern) => pattern.protocol === 'https')).toBe(true);
  });

  it('bounds how long a replaced photograph can keep serving', () => {
    // Thirty days. The cache key is a URL a human controls, so a year is wrong
    // until §13.1's immutable keys exist.
    expect(config.minimumCacheTTL).toBe(60 * 60 * 24 * 30);
  });

  it('does not serve SVG through the optimiser', () => {
    // An SVG is a script that happens to draw, served from the origin that
    // holds the session cookie.
    expect(config.dangerouslyAllowSVG).toBe(false);
  });
});
