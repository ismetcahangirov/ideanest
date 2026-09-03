import { describe, expect, it } from 'vitest';
import { dayBounds, trailQuery } from './audit';

/**
 * The audit trail's date range — issue #404.
 *
 * <p>The trail had no date range at all, on the argument that the order *is* the date: a
 * UUID v7 carries the millisecond it was minted in. That was wrong twice over. The
 * identifier and `occurred_at` are written by two different clocks, which is the ordering
 * defect #404 opened with; and "what did this person do last Tuesday" is the question an
 * audit log exists to answer, which was being answered by paging until the dates stopped
 * being interesting.
 */
describe('dayBounds', () => {
  it('turns a chosen day into the reader’s own midnight, not UTC’s', () => {
    /*
     * An `<input type="date">` yields `2026-09-02` with no zone, and a moderator asking
     * about the second of September means the second of September where they are. Parsing
     * the string as UTC would give somebody in Baku a window starting at three in the
     * morning — and the row they were looking for would be in the previous day's page.
     */
    expect(dayBounds('2026-09-02', 'from')).toBe(new Date(2026, 8, 2).toISOString());
  });

  it('makes the upper bound the next midnight, so one day is a whole day', () => {
    // The service's `to` is exclusive. "From the 2nd to the 2nd" has to be
    // [2 Sep 00:00, 3 Sep 00:00) or it is one instant and matches nothing.
    expect(dayBounds('2026-09-02', 'to')).toBe(new Date(2026, 8, 3).toISOString());
  });

  it('rolls over the end of a month rather than producing the 32nd', () => {
    expect(dayBounds('2026-08-31', 'to')).toBe(new Date(2026, 8, 1).toISOString());
  });

  it('rolls over the end of a year', () => {
    expect(dayBounds('2026-12-31', 'to')).toBe(new Date(2027, 0, 1).toISOString());
  });

  it('is no bound at all for an empty box, which is how the filter is cleared', () => {
    expect(dayBounds('', 'from')).toBeNull();
    expect(dayBounds('   ', 'to')).toBeNull();
  });

  it('is no bound for anything that is not a date, rather than an invalid instant', () => {
    // A browser without a date control falls back to a text field, and what somebody types
    // into one is not always a date. Sending `Invalid Date` would be a 400 they cannot act
    // on; sending nothing is the unfiltered trail, which is what they can already see.
    expect(dayBounds('last tuesday', 'from')).toBeNull();
    expect(dayBounds('02/09/2026', 'from')).toBeNull();
  });
});

describe('trailQuery', () => {
  it('carries the range and the actor to the service', () => {
    const query = trailQuery({
      actorId: 'aaaaaaaa-0000-4000-8000-000000000001',
      from: '2026-09-02T00:00:00.000Z',
      to: '2026-09-03T00:00:00.000Z',
    });

    const parameters = new URLSearchParams(query);
    expect(parameters.get('actorId')).toBe('aaaaaaaa-0000-4000-8000-000000000001');
    expect(parameters.get('from')).toBe('2026-09-02T00:00:00.000Z');
    expect(parameters.get('to')).toBe('2026-09-03T00:00:00.000Z');
  });

  it('leaves out a bound that is absent rather than sending an empty one', () => {
    // An empty `from` would be a 400 from the binder, on a screen where the reader has
    // simply not chosen a start date.
    const parameters = new URLSearchParams(trailQuery({ from: null, to: null }));

    expect(parameters.has('from')).toBe(false);
    expect(parameters.has('to')).toBe(false);
  });
});
