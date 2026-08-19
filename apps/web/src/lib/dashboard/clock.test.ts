import { describe, expect, it } from 'vitest';
import {
  URGENT_THRESHOLD_MS,
  clockSkewMs,
  describeRemaining,
  isUrgent,
  remainingMs,
  splitRemaining,
  tickIntervalMs,
} from './clock';

/**
 * The arithmetic behind "time remaining", tested where it can be tested exactly.
 *
 * The service sends two instants rather than a countdown so that this module can correct
 * for the reader's clock. That correction is the part worth checking, and it is checked
 * at the boundaries — the last minute, the moment of expiry, a reader who is a day out —
 * because those are the cases the obvious implementation gets wrong and the ones nobody
 * reproduces by hand.
 */

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** Noon, so the arithmetic below reads as offsets from a round number. */
const SERVER_NOW = Date.parse('2026-08-20T12:00:00.000Z');

describe('clock skew', () => {
  it('is zero when the reader agrees with the service', () => {
    expect(clockSkewMs('2026-08-20T12:00:00.000Z', SERVER_NOW)).toBe(0);
  });

  it('is positive when the reader is ahead', () => {
    expect(clockSkewMs('2026-08-20T12:00:00.000Z', SERVER_NOW + 40 * MINUTE)).toBe(40 * MINUTE);
  });

  it('is negative when the reader is behind', () => {
    expect(clockSkewMs('2026-08-20T12:00:00.000Z', SERVER_NOW - 90 * SECOND)).toBe(-90 * SECOND);
  });

  /**
   * A NaN here would propagate through every subtraction afterwards and render as
   * "NaN days left" — which is worse than an uncorrected countdown, because it is a
   * number nobody can act on rather than one that is slightly off.
   */
  it('is zero rather than NaN when the instant cannot be parsed', () => {
    expect(clockSkewMs('not an instant', SERVER_NOW)).toBe(0);
  });
});

describe('time remaining', () => {
  const deadline = '2026-08-25T12:00:00.000Z';

  it('counts down against the service clock, not the reader s', () => {
    // A reader forty minutes fast. Without the correction they would be told the
    // campaign closes forty minutes before it does.
    const skew = 40 * MINUTE;
    expect(remainingMs(deadline, SERVER_NOW + skew, skew)).toBe(5 * DAY);
  });

  it('is null when there is no deadline, which is not the same as zero', () => {
    expect(remainingMs(null, SERVER_NOW, 0)).toBeNull();
    expect(remainingMs(undefined, SERVER_NOW, 0)).toBeNull();
  });

  it('never goes negative', () => {
    expect(remainingMs(deadline, Date.parse('2026-09-01T00:00:00.000Z'), 0)).toBe(0);
  });

  it('is null rather than NaN on an unparseable deadline', () => {
    expect(remainingMs('soon', SERVER_NOW, 0)).toBeNull();
  });
});

describe('splitting a duration', () => {
  it('breaks a span into whole units', () => {
    expect(splitRemaining(2 * DAY + 3 * HOUR + 4 * MINUTE + 5 * SECOND)).toEqual({
      days: 2,
      hours: 3,
      minutes: 4,
      seconds: 5,
      expired: false,
    });
  });

  it('reports zero as expired', () => {
    expect(splitRemaining(0).expired).toBe(true);
  });
});

describe('the sentence a countdown reads as', () => {
  it('shows days while there are days', () => {
    expect(describeRemaining(splitRemaining(27 * DAY))).toBe('27 days left');
    expect(describeRemaining(splitRemaining(DAY + HOUR))).toBe('1 day left');
  });

  it('shows hours and minutes inside a day', () => {
    expect(describeRemaining(splitRemaining(5 * HOUR + 12 * MINUTE))).toBe('5h 12m left');
  });

  /**
   * Seconds appear only in the last hour, when they are the thing being watched. Showing
   * them three weeks out would be a number that changes every second and says nothing.
   */
  it('shows seconds only in the last hour', () => {
    expect(describeRemaining(splitRemaining(2 * MINUTE + 30 * SECOND))).toBe('2m 30s left');
    expect(describeRemaining(splitRemaining(45 * SECOND))).toBe('45s left');
    expect(describeRemaining(splitRemaining(90 * MINUTE))).not.toContain('s left');
  });

  it('says the campaign closed rather than counting past zero', () => {
    expect(describeRemaining(splitRemaining(0))).toBe('Closed');
  });
});

describe('how often it ticks', () => {
  /**
   * The optimisation this exists for: a page showing "27 days left" that re-rendered
   * every second would wake the main thread 86,400 times to change nothing, on a screen
   * creators leave open.
   */
  it('ticks once a minute while it is showing minutes or days', () => {
    expect(tickIntervalMs(27 * DAY)).toBe(MINUTE);
    expect(tickIntervalMs(5 * HOUR)).toBe(MINUTE);
  });

  it('ticks once a second in the last hour, when seconds are shown', () => {
    expect(tickIntervalMs(59 * MINUTE)).toBe(SECOND);
  });

  it('stops working hard once the campaign has closed', () => {
    expect(tickIntervalMs(0)).toBe(HOUR);
  });
});

describe('urgency', () => {
  /**
   * ui-kit §8.1's threshold, and the only thing lime is allowed to mean. Checked on both
   * sides of the boundary because "closing soon" turning on a day early would put an
   * urgent colour on a campaign with two days left.
   */
  it('is the last forty-eight hours and not a minute more', () => {
    expect(isUrgent(URGENT_THRESHOLD_MS)).toBe(true);
    expect(isUrgent(URGENT_THRESHOLD_MS + MINUTE)).toBe(false);
  });

  it('is not urgent once it has closed, and not urgent without a deadline', () => {
    expect(isUrgent(0)).toBe(false);
    expect(isUrgent(null)).toBe(false);
  });
});
