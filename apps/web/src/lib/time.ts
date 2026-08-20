/**
 * Rendering an instant for a reader.
 *
 * Lifted out of `lib/sessions/describe.ts` when the notification inbox (#88) needed the
 * same two functions. Nothing about "3 hours ago" is about a session, and the alternative
 * — a second module importing `lib/sessions` for a formatter — would have made the inbox
 * depend on the device list for no reason anybody could find later.
 *
 * `lib/sessions/describe.ts` re-exports both, so its own callers and tests are unchanged.
 *
 * The locale is pinned to English until internationalised routing lands (#123). That is a
 * limitation rather than a decision: these strings are the only record on screen of when
 * something happened, and a reader in Azerbaijani currently gets them in English.
 */

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
 * `now` is a parameter rather than a call to `Date.now()` so that the function is pure and
 * the tests do not have to freeze the clock.
 */
export function formatRelativeTime(iso: string, now: Date): string {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return 'Unknown';

  let duration = (then.getTime() - now.getTime()) / 1000;

  // Under a minute, a rounded figure is noise.
  //
  // Lower case, because every string this returns is read inside a sentence:
  // "Last active just now", "3 hours ago".
  if (Math.abs(duration) < 45) return 'just now';

  for (const [amount, unit] of DIVISIONS) {
    if (Math.abs(duration) < amount) return RELATIVE.format(Math.round(duration), unit);
    duration /= amount;
  }

  return RELATIVE.format(Math.round(duration), 'year');
}

/** The exact timestamp, for the `title` of the relative one. */
const EXACT = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' });

export function formatExactTime(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return 'Unknown';

  return EXACT.format(at);
}
