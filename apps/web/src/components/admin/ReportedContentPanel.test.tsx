import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ReportedContent } from '../../lib/admin/reported-content';
import { NO_NAMES } from '../../lib/admin/directory';
import { ReportedContentPanel } from './ReportedContentPanel';
import { translatorFor } from '../../test-copy';
import { consoleChromeCopyFrom } from '../../lib/i18n/admin/common-copy';
import { reportDetailCopyFrom } from '../../lib/i18n/admin/content-copy';

/*
 * Copy built from `messages/en.json` through the same builder the route calls, for the
 * reason `src/test-copy.ts` gives: a suite that retyped the sentences would stay green with
 * the catalogue empty.
 */
const CHROME = consoleChromeCopyFrom(translatorFor('admin'), translatorFor('common'));
const COPY = reportDetailCopyFrom(translatorFor('admin'), CHROME);

const COMMENT: ReportedContent = {
  targetType: 'COMMENT',
  state: 'PRESENT',
  body: 'Free download here, follow the link.',
  authorId: 'c0ffee00-0000-4000-8000-000000000001',
  project: {
    id: 'a1b2c3d4-0000-4000-8000-000000000001',
    title: 'Xari Bulbul Ceramics',
    slug: 'xari-bulbul-ceramics',
    creatorSlug: 'aysel-studio',
  },
  createdAt: '2026-08-24T09:00:00.000Z',
};

function panel(content: ReportedContent | null, error: string | null = null) {
  return (
    <ReportedContentPanel
      content={content}
      error={error}
      names={NO_NAMES}
      locale="en"
      copy={COPY.evidence}
      identity={CHROME.identity}
    />
  );
}

/**
 * What was reported — issue #399.
 *
 * <p>The defect this closes is not that the page was thin. A moderator was asked to uphold
 * or dismiss a complaint about a comment they could not read, and the fast safe answer to
 * that is always to dismiss — so the queue looked worked and was not.
 */
describe('the reported content', () => {
  it('shows the comment the complaint is about, which is the whole of #399', () => {
    render(panel(COMMENT));

    // The sentence a decision is taken on. It was on no screen in the console.
    expect(screen.getByText('Free download here, follow the link.')).toBeInTheDocument();
  });

  it('links the campaign to the staff preview rather than to a public page that may 404', () => {
    render(panel(COMMENT));

    const link = screen.getByRole('link', { name: /Xari Bulbul Ceramics/u });
    // Not `/projects/{creatorSlug}/{slug}`, even though the response carries both halves: a
    // comment can be reported on a campaign that is later suspended, and a suspended
    // campaign has no public page.
    expect(link).toHaveAttribute('href', '/en/admin/campaigns/a1b2c3d4-0000-4000-8000-000000000001');
    // A new tab, so a decision half-taken on the report survives the trip.
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('still shows a removed comment, and says somebody has already taken it down', () => {
    render(panel({ ...COMMENT, state: 'REMOVED' }));

    // The service keeps the row and its body for exactly this: a report filed before the
    // removal still has to be decidable, and a moderator told only "removed" cannot tell an
    // upheld report from a dismissed one.
    expect(screen.getByText('Free download here, follow the link.')).toBeInTheDocument();
    expect(screen.getByText(COPY.evidence.removedTitle)).toBeInTheDocument();
  });

  it('distinguishes content that has been purged from content that was taken down', () => {
    render(panel({ targetType: 'COMMENT', state: 'GONE' }));

    expect(screen.getByText(COPY.evidence.goneTitle)).toBeInTheDocument();
    expect(screen.queryByText(COPY.evidence.removedTitle)).not.toBeInTheDocument();
  });

  it('offers a campaign report a link rather than a fragment of the campaign', () => {
    render(
      panel({
        targetType: 'PROJECT',
        state: 'ADDRESSED_DIRECTLY',
        project: COMMENT.project,
      }),
    );

    // A blurb next to a link to the page it came from is worse than the link alone.
    expect(screen.getByRole('link', { name: /Xari Bulbul Ceramics/u })).toBeInTheDocument();
    expect(screen.getByText(COPY.evidence.addressedBody)).toBeInTheDocument();
  });

  it('names an update by its number, which is what a creator and a backer both call it', () => {
    render(
      panel({
        targetType: 'PROJECT_UPDATE',
        state: 'PRESENT',
        number: 4,
        title: 'The kilns are late',
        body: 'We have moved to a second workshop.',
        project: COMMENT.project,
      }),
    );

    expect(screen.getByText('Update 4')).toBeInTheDocument();
    expect(screen.getByText('The kilns are late')).toBeInTheDocument();
  });

  it('says the evidence is missing rather than losing the page when the read fails', () => {
    render(panel(null, 'The reported content could not be reached.'));

    // The report and its decisions stay on screen. A moderator who cannot load the evidence
    // must still be able to see the complaint, the reporter and the history.
    expect(screen.getByText(COPY.evidence.failedTitle)).toBeInTheDocument();
    expect(screen.getByText('The reported content could not be reached.')).toBeInTheDocument();
  });
});
