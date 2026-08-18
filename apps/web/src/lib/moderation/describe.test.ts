import { describe, expect, it } from 'vitest';
import type { QueuedReport } from './api';
import {
  DEFAULT_FILTERS,
  OVERDUE_AFTER_MS,
  formatRelativeTime,
  humaniseState,
  isOverdue,
  isRefined,
  isRepeated,
  openReportsLabel,
  refine,
  shortId,
} from './describe';

const NOW = new Date('2026-08-18T12:00:00.000Z');

function report(overrides: Partial<QueuedReport> = {}): QueuedReport {
  return {
    id: 'r-1',
    target: { type: 'PROJECT', id: 'a1b2c3d4-0000-4000-8000-000000000001' },
    openReportsOnTarget: 1,
    reporterId: 'u1',
    reason: 'FRAUD',
    state: 'OPEN',
    createdAt: NOW.toISOString(),
    ...overrides,
  };
}

function hoursAgo(hours: number): string {
  return new Date(NOW.getTime() - hours * 60 * 60 * 1000).toISOString();
}

/**
 * Appearance is reviewed in Storybook. These cover the decisions this module
 * makes on the queue's behalf — what counts as urgent, and what a filter hides.
 */
describe('moderation/describe', () => {
  describe('isOverdue', () => {
    it('calls an open report urgent once it has waited longer than the threshold', () => {
      expect(isOverdue(report({ createdAt: hoursAgo(49) }), NOW)).toBe(true);
      expect(OVERDUE_AFTER_MS).toBe(48 * 60 * 60 * 1000);
    });

    it('leaves a report that has waited less than the threshold alone', () => {
      expect(isOverdue(report({ createdAt: hoursAgo(3) }), NOW)).toBe(false);
    });

    it('never calls a decided report overdue — a finished decision is not late', () => {
      const decided = report({
        createdAt: hoursAgo(500),
        state: 'UPHELD',
        resolution: { moderatorId: 'm-1', at: hoursAgo(400) },
      });

      expect(isOverdue(decided, NOW)).toBe(false);
    });

    it('does not treat an unparseable timestamp as infinitely old', () => {
      expect(isOverdue(report({ createdAt: 'not a date' }), NOW)).toBe(false);
    });
  });

  describe('isRepeated', () => {
    it('is the service’s own triage signal: more than one open complaint', () => {
      expect(isRepeated(report({ openReportsOnTarget: 1 }))).toBe(false);
      expect(isRepeated(report({ openReportsOnTarget: 2 }))).toBe(true);
    });
  });

  describe('openReportsLabel', () => {
    it('agrees with itself about number and says what the target is', () => {
      expect(openReportsLabel(report({ openReportsOnTarget: 1 }))).toBe(
        '1 open report on this campaign',
      );
      expect(
        openReportsLabel(
          report({ openReportsOnTarget: 14, target: { type: 'USER', id: 'u9u8u7u6' } }),
        ),
      ).toBe('14 open reports on this account');
    });
  });

  describe('refine', () => {
    const campaign = report({ id: 'campaign' });
    const account = report({ id: 'account', target: { type: 'USER', id: 'u9u8u7u6' } });
    const stale = report({ id: 'stale', createdAt: hoursAgo(72) });
    const piledOn = report({ id: 'piled-on', openReportsOnTarget: 9 });
    const all = [campaign, account, stale, piledOn];

    it('hides nothing by default', () => {
      expect(refine(all, DEFAULT_FILTERS, NOW)).toHaveLength(4);
      expect(isRefined(DEFAULT_FILTERS)).toBe(false);
    });

    it('narrows to one kind of target', () => {
      const only = refine(all, { ...DEFAULT_FILTERS, target: 'USER' }, NOW);
      expect(only.map((row) => row.id)).toEqual(['account']);
    });

    it('narrows to what has waited too long', () => {
      const only = refine(all, { ...DEFAULT_FILTERS, overdueOnly: true }, NOW);
      expect(only.map((row) => row.id)).toEqual(['stale']);
    });

    it('narrows to targets more than one person has complained about', () => {
      const only = refine(all, { ...DEFAULT_FILTERS, repeatedOnly: true }, NOW);
      expect(only.map((row) => row.id)).toEqual(['piled-on']);
    });

    it('combines the narrowings rather than choosing between them', () => {
      const both = refine(
        [...all, report({ id: 'both', createdAt: hoursAgo(72), openReportsOnTarget: 4 })],
        { ...DEFAULT_FILTERS, overdueOnly: true, repeatedOnly: true },
        NOW,
      );
      expect(both.map((row) => row.id)).toEqual(['both']);
    });

    it('reports that it is hiding something, which is what the count line is for', () => {
      expect(isRefined({ ...DEFAULT_FILTERS, target: 'PROJECT' })).toBe(true);
      expect(isRefined({ ...DEFAULT_FILTERS, overdueOnly: true })).toBe(true);
      expect(isRefined({ ...DEFAULT_FILTERS, repeatedOnly: true })).toBe(true);
    });

    it('changing the state alone is not a client-side narrowing — the service does that', () => {
      expect(isRefined({ ...DEFAULT_FILTERS, state: 'UPHELD' })).toBe(false);
    });
  });

  describe('formatRelativeTime', () => {
    it('reads inside a sentence', () => {
      expect(formatRelativeTime(hoursAgo(3), NOW)).toBe('3 hours ago');
      expect(formatRelativeTime(NOW.toISOString(), NOW)).toBe('just now');
    });

    it('admits it does not know rather than printing "Invalid Date"', () => {
      expect(formatRelativeTime('not a date', NOW)).toBe('an unknown time ago');
    });
  });

  describe('humaniseState', () => {
    it('turns a service constant into a sentence fragment', () => {
      expect(humaniseState('CHANGES_REQUESTED')).toBe('changes requested');
    });
  });

  describe('shortId', () => {
    it('gives a card a name it can be told apart by', () => {
      expect(shortId('a1b2c3d4-0000-4000-8000-000000000001')).toBe('a1b2c3d4');
    });
  });
});
