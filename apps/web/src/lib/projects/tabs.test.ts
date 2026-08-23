import { describe, expect, it } from 'vitest';
import {
  CAMPAIGN_TABS,
  campaignCursorFrom,
  campaignTabFrom,
  campaignTabHref,
} from './tabs';

/**
 * The campaign page's tabs, as addresses — #282, #284, #285.
 *
 * WHAT THESE COVER, and why each one is worth a test rather than a comment:
 *
 *   - **the default tab has exactly one address.** `?tab=campaign` and the bare path are the
 *     same page, and the moment this module produces both, the canonical URL, the sitemap
 *     entry and the link somebody pastes into a message become three strings for one
 *     campaign.
 *   - **an unknown tab is the campaign, never a refusal.** A mistyped query parameter must
 *     not be able to take a real campaign off the internet.
 *   - **a repeated parameter does not silently take the wrong branch.** Next hands
 *     `?tab=a&tab=b` over as an array, and a reader that assumed a string would compare an
 *     array with a string and land on the default without anybody noticing which one was
 *     meant.
 *   - **the cursor is carried, not interpreted.** An update cursor is an integer and a
 *     comment cursor is a UUID; this module is told to read neither, and the test is what
 *     stops somebody adding a "sensible" format check that refuses the next encoding.
 */

const PATH = '/projects/ayan/coffee-table-book';

describe('reading the tab from the address', () => {
  it('answers the default for an absent parameter', () => {
    expect(campaignTabFrom(undefined)).toBe('campaign');
  });

  it('answers the default rather than refusing an unknown tab', () => {
    expect(campaignTabFrom('nonsense')).toBe('campaign');
  });

  it('reads each tab it publishes', () => {
    for (const tab of CAMPAIGN_TABS) {
      expect(campaignTabFrom(tab.id)).toBe(tab.id);
    }
  });

  it('is not case sensitive, because a link is typed by people', () => {
    expect(campaignTabFrom('Comments')).toBe('comments');
  });

  it('takes the first of a repeated parameter rather than refusing the page', () => {
    expect(campaignTabFrom(['updates', 'comments'])).toBe('updates');
  });
});

describe('building a tab address', () => {
  it('gives the default tab the bare path and no parameter', () => {
    expect(campaignTabHref(PATH, 'campaign')).toBe(PATH);
  });

  it('names every other tab in the query string', () => {
    expect(campaignTabHref(PATH, 'comments')).toBe(`${PATH}?tab=comments`);
  });

  it('carries a cursor beside the tab', () => {
    expect(campaignTabHref(PATH, 'updates', { cursor: '7' })).toBe(`${PATH}?tab=updates&from=7`);
  });

  it('carries a thread identifier, which is what "show more replies" links to', () => {
    expect(campaignTabHref(PATH, 'comments', { thread: 'abc' })).toBe(
      `${PATH}?tab=comments&thread=abc`,
    );
  });

  it('drops an empty cursor rather than sending the service an empty parameter', () => {
    expect(campaignTabHref(PATH, 'updates', { cursor: '' })).toBe(`${PATH}?tab=updates`);
    expect(campaignTabHref(PATH, 'updates', { cursor: null })).toBe(`${PATH}?tab=updates`);
  });
});

describe('reading a cursor from the address', () => {
  it('passes an opaque value through untouched', () => {
    expect(campaignCursorFrom('0193f2a1-0000-7000-8000-000000000001')).toBe(
      '0193f2a1-0000-7000-8000-000000000001',
    );
  });

  it('accepts an integer cursor, which is what an update page uses', () => {
    expect(campaignCursorFrom('7')).toBe('7');
  });

  it('answers null for an absent or empty parameter', () => {
    expect(campaignCursorFrom(undefined)).toBeNull();
    expect(campaignCursorFrom('   ')).toBeNull();
  });

  /**
   * A bound rather than a format. The point is not to validate the encoding — this module is
   * told not to read it — but to refuse to put a kilobyte of somebody else's query string
   * into an outbound request to the service.
   */
  it('refuses a value longer than any cursor the service mints', () => {
    expect(campaignCursorFrom('x'.repeat(129))).toBeNull();
    expect(campaignCursorFrom('x'.repeat(128))).toBe('x'.repeat(128));
  });
});
