import type { SessionSummary } from './api';

/**
 * Turning a session row into something a person can recognise.
 *
 * The point of this screen is that a user spots the device that is not theirs.
 * A raw user-agent string does not support that judgement, so the strings here
 * are deliberately coarse: the browser and the platform, and nothing that
 * pretends to more precision than a user-agent can honestly carry.
 */

/**
 * Ordered — first match wins, and the order is the whole trick. Every
 * Chromium-derived browser still says "Chrome", and Chrome and Edge both still
 * say "Safari", so the specific tokens have to be tested before the general
 * ones.
 */
const BROWSERS: ReadonlyArray<readonly [pattern: RegExp, name: string]> = [
  [/\bEdg(?:iOS|A|)\//, 'Edge'],
  [/\b(?:OPR|OPiOS)\//, 'Opera'],
  [/\bSamsungBrowser\//, 'Samsung Internet'],
  [/\b(?:Firefox|FxiOS)\//, 'Firefox'],
  [/\b(?:Chrome|CriOS)\//, 'Chrome'],
  [/\bSafari\//, 'Safari'],
];

/**
 * Also ordered. An Android user-agent contains "Linux", and an iOS one
 * contains "like Mac OS X", so the narrower platform is tested first.
 */
const PLATFORMS: ReadonlyArray<readonly [pattern: RegExp, name: string]> = [
  [/\bAndroid\b/, 'Android'],
  [/\b(?:iPhone|iPad|iPod)\b/, 'iOS'],
  [/\bCrOS\b/, 'ChromeOS'],
  [/\b(?:Mac OS X|Macintosh)\b/, 'macOS'],
  [/\bWindows\b/, 'Windows'],
  [/\bLinux\b/, 'Linux'],
];

function firstMatch(
  table: ReadonlyArray<readonly [RegExp, string]>,
  userAgent: string | undefined,
): string | null {
  if (!userAgent) return null;

  for (const [pattern, name] of table) {
    if (pattern.test(userAgent)) return name;
  }
  return null;
}

export function browserOf(userAgent: string | undefined): string | null {
  return firstMatch(BROWSERS, userAgent);
}

export function platformOf(userAgent: string | undefined): string | null {
  return firstMatch(PLATFORMS, userAgent);
}

/**
 * What the row is called.
 *
 * The label the client sent at sign-in wins, because a person who named their
 * laptop knows better than a parser does. Failing that, browser and platform.
 * Failing that, a plain admission — an invented name on a security screen is
 * worse than no name, because the user would have to decide whether to trust it.
 */
export function deviceNameOf(
  session: Pick<SessionSummary, 'deviceLabel' | 'userAgent'>,
): string {
  const label = session.deviceLabel?.trim();
  if (label) return label;

  const browser = browserOf(session.userAgent);
  const platform = platformOf(session.userAgent);

  if (browser && platform) return `${browser} on ${platform}`;
  return browser ?? platform ?? 'Unknown device';
}

const RELATIVE = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

const DIVISIONS: ReadonlyArray<readonly [amount: number, unit: Intl.RelativeTimeFormatUnit]> = [
  [60, 'second'],
  [60, 'minute'],
  [24, 'hour'],
  [7, 'day'],
  [4.34524, 'week'],
  [12, 'month'],
  [Number.POSITIVE_INFINITY, 'year'],
];

/**
 * "3 hours ago", "yesterday", "just now".
 *
 * `now` is a parameter rather than a call to `Date.now()` so that the function
 * is pure and the tests do not have to freeze the clock.
 */
export function formatRelativeTime(iso: string, now: Date): string {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return 'Unknown';

  let duration = (then.getTime() - now.getTime()) / 1000;

  // Under a minute, a rounded figure is noise. `lastSeenAt` only advances when
  // the session refreshes, so it is never as precise as seconds imply anyway.
  // Lower case, because every string this returns is read inside a sentence:
  // "Last active just now", "Last active 3 hours ago".
  if (Math.abs(duration) < 45) return 'just now';

  for (const [amount, unit] of DIVISIONS) {
    if (Math.abs(duration) < amount) return RELATIVE.format(Math.round(duration), unit);
    duration /= amount;
  }

  return RELATIVE.format(Math.round(duration), 'year');
}

/**
 * The exact timestamp, for the `title` of the relative one.
 *
 * The locale is pinned until internationalised routing lands (#123); the point
 * here is that a relative string is never the only record of when something
 * happened on a security screen.
 */
const EXACT = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' });

export function formatExactTime(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return 'Unknown';

  return EXACT.format(at);
}

/**
 * The second line of a row: where the session is signed in from.
 *
 * The address is what the service saw on the socket. It is not derived from
 * `X-Forwarded-For`, so behind an un-configured proxy it can be the proxy's own
 * address — which is why the row says "IP address" rather than a place.
 */
export function locationOf(session: Pick<SessionSummary, 'ipAddress'>): string | null {
  const address = session.ipAddress?.trim();
  return address ? address : null;
}
