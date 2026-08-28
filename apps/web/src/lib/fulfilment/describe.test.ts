import { describe, expect, it } from 'vitest';
import { describeStatus, isFollowableTrackingUrl } from './describe';
import { deliveryListCopyFrom } from '../i18n/fulfilment-copy';
import { translatorFor } from '../../test-copy';
/*
 * The copy the route would have resolved, built from `messages/en.json` by the same function it
 * calls — issue #324. Retyping the sentences here would give a test that passes whatever the
 * catalogue says, which is the opposite of what it is for.
 */
const COPY = deliveryListCopyFrom(translatorFor('account.fulfilment'));

/**
 * §4.8's PM-09 and PM-10 — issue #290.
 *
 * WHAT THESE COVER:
 *
 *   - **a tracking URL is a creator's typed text and ends up in an `href`.** `javascript:` in
 *     an anchor is script execution on this origin, which is where the session lives. This is
 *     the assertion that keeps that closed; it fails loudly if somebody later "simplifies" the
 *     check to a truthiness test.
 *   - every status is paired with words, because docs/ui-kit.md §9.2 forbids colour as the
 *     only carrier — and a `Tag` variant is colour.
 *   - an unknown status renders as itself rather than as a blank or a guess.
 */

describe('describeStatus', () => {
  it('gives every known status a label and a sentence', () => {
    for (const status of ['PREPARING', 'SHIPPED', 'DELIVERED', 'RETURNED'] as const) {
      const described = describeStatus(status, COPY);
      expect(described.label.trim()).not.toBe('');
      expect(described.detail.trim()).not.toBe('');
    }
  });

  it('marks only a returned parcel as needing attention', () => {
    expect(describeStatus('RETURNED', COPY).tone).toBe('danger');
    expect(describeStatus('DELIVERED', COPY).tone).toBe('success');
    expect(describeStatus('PREPARING', COPY).tone).toBe('default');
  });

  it('falls back to the service’s own word for a status this build does not know', () => {
    const described = describeStatus('LOST_IN_TRANSIT', COPY);
    expect(described.label).toBe('LOST_IN_TRANSIT');
    expect(described.tone).toBe('default');
  });
});

describe('isFollowableTrackingUrl', () => {
  it('allows http and https', () => {
    expect(isFollowableTrackingUrl('https://carrier.example/track/AB123')).toBe(true);
    expect(isFollowableTrackingUrl('http://carrier.example/track/AB123')).toBe(true);
  });

  it('refuses a scheme that would execute or substitute a document', () => {
    expect(isFollowableTrackingUrl('javascript:alert(document.cookie)')).toBe(false);
    expect(isFollowableTrackingUrl('data:text/html,<script>1</script>')).toBe(false);
    expect(isFollowableTrackingUrl('vbscript:msgbox(1)')).toBe(false);
  });

  it('refuses what is not a URL at all, which is what most tracking numbers are', () => {
    expect(isFollowableTrackingUrl(null)).toBe(false);
    expect(isFollowableTrackingUrl('')).toBe(false);
    expect(isFollowableTrackingUrl('   ')).toBe(false);
    expect(isFollowableTrackingUrl('AB123456789AZ')).toBe(false);
  });
});
