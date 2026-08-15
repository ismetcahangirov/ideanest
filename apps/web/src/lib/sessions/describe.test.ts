import { describe, expect, it } from 'vitest';
import {
  browserOf,
  deviceNameOf,
  formatExactTime,
  formatRelativeTime,
  locationOf,
  platformOf,
} from './describe';

const CHROME_MAC =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const SAFARI_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1';
const EDGE_WINDOWS =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0';
const FIREFOX_LINUX = 'Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0';
const CHROME_ANDROID =
  'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';

describe('browserOf', () => {
  it('reads the plain cases', () => {
    expect(browserOf(FIREFOX_LINUX)).toBe('Firefox');
    expect(browserOf(CHROME_MAC)).toBe('Chrome');
  });

  // Every Chromium browser still claims to be Chrome, and Chrome still claims
  // to be Safari. Getting these wrong mislabels most of the list.
  it('prefers the specific token over the one every browser inherits', () => {
    expect(browserOf(EDGE_WINDOWS)).toBe('Edge');
    expect(browserOf(CHROME_ANDROID)).toBe('Chrome');
  });

  it('reads Safari only when nothing more specific claims the string', () => {
    expect(browserOf(SAFARI_IPHONE)).toBe('Safari');
  });

  it('answers null rather than guessing', () => {
    expect(browserOf(undefined)).toBeNull();
    expect(browserOf('curl/8.7.1')).toBeNull();
  });
});

describe('platformOf', () => {
  it('reads the plain cases', () => {
    expect(platformOf(CHROME_MAC)).toBe('macOS');
    expect(platformOf(EDGE_WINDOWS)).toBe('Windows');
  });

  // An Android user-agent contains "Linux"; an iOS one contains "like Mac OS X".
  it('prefers the narrower platform over the one it is built on', () => {
    expect(platformOf(CHROME_ANDROID)).toBe('Android');
    expect(platformOf(SAFARI_IPHONE)).toBe('iOS');
    expect(platformOf(FIREFOX_LINUX)).toBe('Linux');
  });

  it('answers null rather than guessing', () => {
    expect(platformOf(undefined)).toBeNull();
  });
});

describe('deviceNameOf', () => {
  it('prefers the label the client sent at sign-in', () => {
    expect(deviceNameOf({ deviceLabel: "İsmət's MacBook", userAgent: CHROME_MAC })).toBe(
      "İsmət's MacBook",
    );
  });

  it('ignores a label that is only whitespace', () => {
    expect(deviceNameOf({ deviceLabel: '   ', userAgent: CHROME_MAC })).toBe('Chrome on macOS');
  });

  it('falls back to browser and platform', () => {
    expect(deviceNameOf({ userAgent: SAFARI_IPHONE })).toBe('Safari on iOS');
  });

  it('uses whichever half it could read', () => {
    expect(deviceNameOf({ userAgent: 'Mozilla/5.0 (Windows NT 10.0)' })).toBe('Windows');
  });

  // An invented name on a security screen is worse than none: the user then has
  // to decide whether to trust it.
  it('admits when it knows nothing', () => {
    expect(deviceNameOf({})).toBe('Unknown device');
    expect(deviceNameOf({ userAgent: 'something-unparseable' })).toBe('Unknown device');
  });
});

describe('formatRelativeTime', () => {
  const now = new Date('2026-08-15T12:00:00.000Z');

  it('does not pretend to second-level precision', () => {
    expect(formatRelativeTime('2026-08-15T11:59:40.000Z', now)).toBe('just now');
    expect(formatRelativeTime('2026-08-15T12:00:00.000Z', now)).toBe('just now');
  });

  it('steps up through the units', () => {
    expect(formatRelativeTime('2026-08-15T11:55:00.000Z', now)).toBe('5 minutes ago');
    expect(formatRelativeTime('2026-08-15T09:00:00.000Z', now)).toBe('3 hours ago');
    expect(formatRelativeTime('2026-08-14T12:00:00.000Z', now)).toBe('yesterday');
    expect(formatRelativeTime('2026-08-11T12:00:00.000Z', now)).toBe('4 days ago');
    expect(formatRelativeTime('2026-06-15T12:00:00.000Z', now)).toBe('2 months ago');
  });

  // `expiresAt` is in the future, and the same formatter renders it.
  it('handles an instant that has not happened yet', () => {
    expect(formatRelativeTime('2026-08-16T12:00:00.000Z', now)).toBe('tomorrow');
  });

  it('says so rather than rendering "Invalid Date"', () => {
    expect(formatRelativeTime('not-a-date', now)).toBe('Unknown');
  });
});

describe('formatExactTime', () => {
  it('renders a real instant', () => {
    expect(formatExactTime('2026-08-15T12:00:00.000Z')).toContain('2026');
  });

  it('says so rather than rendering "Invalid Date"', () => {
    expect(formatExactTime('')).toBe('Unknown');
  });
});

describe('locationOf', () => {
  it('returns the address when there is one', () => {
    expect(locationOf({ ipAddress: '203.0.113.7' })).toBe('203.0.113.7');
  });

  // Stripped at account anonymisation, and absent from the JSON when null.
  it('returns null when the service recorded none', () => {
    expect(locationOf({})).toBeNull();
    expect(locationOf({ ipAddress: '  ' })).toBeNull();
  });
});
