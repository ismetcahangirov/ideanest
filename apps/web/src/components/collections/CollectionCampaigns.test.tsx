import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { getCollectionCampaigns } from '../../lib/collections/api';
import type { ProjectCard } from '../../lib/discovery/api';
import { CollectionCampaigns } from './CollectionCampaigns';

/**
 * A collection's campaigns, in the curator's order — D-08, issue #266.
 *
 * WHAT THESE COVER:
 *
 *   - **the first page is never re-requested.** It came from the server render so that the
 *     campaigns are in the HTML a crawler receives; asking for it again would replace content
 *     already on screen and spend a round trip on the platform's own crawl path.
 *   - the button appends and the cursor advances, and it is ABSENT on the last page — a client
 *     that offered "show more" over nothing would be one that answers with silence.
 *   - **a failed page does not destroy the pages already read.** A reader who has loaded two
 *     pages keeps two pages; blanking a list somebody is reading is worse than saying the next
 *     page did not arrive.
 *   - the control names the collection, so two "Show more" buttons in one document are two a
 *     screen reader can tell apart (docs/ui-kit.md §9.4).
 *
 * The reader is stubbed rather than the network, because what is under test is this
 * component's behaviour and not whether the service answers.
 */

vi.mock('../../lib/collections/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/collections/api')>()),
  getCollectionCampaigns: vi.fn(),
}));

const readMock = vi.mocked(getCollectionCampaigns);

function card(overrides: Partial<ProjectCard> = {}): ProjectCard {
  return {
    id: 'p1',
    slug: 'a-campaign',
    creatorSlug: 'aysel',
    title: 'A campaign',
    creator: { name: 'Aysel', slug: 'aysel' },
    pledged: { amount: '1200.00', currency: 'AZN' },
    goal: { amount: '2000.00', currency: 'AZN' },
    completionPercent: '60.00',
    backersCount: 12,
    daysLeft: 9,
    badge: 'live',
    state: 'LIVE',
    ...overrides,
  };
}

beforeEach(() => {
  readMock.mockReset();
});

afterEach(cleanup);

function renderList(cursor: string | null, items: readonly ProjectCard[] = [card()]) {
  return render(
    <CollectionCampaigns
      slug="spring-2026"
      title="Spring 2026"
      initial={items}
      initialCursor={cursor}
    />,
  );
}

describe('the seeded first page', () => {
  it('renders without asking the service for anything', () => {
    renderList(null);

    expect(screen.getByRole('link', { name: 'A campaign' })).toBeInTheDocument();
    expect(readMock).not.toHaveBeenCalled();
  });

  it('names the grid after the collection, since the heading is outside it', () => {
    renderList(null);

    expect(screen.getByRole('list', { name: 'Campaigns in Spring 2026' })).toBeInTheDocument();
  });

  it('states how many are shown rather than leaving them to be counted', () => {
    renderList(null);

    expect(screen.getByText(/1 campaign shown/u)).toBeInTheDocument();
  });

  it('offers nothing more on the last page', () => {
    renderList(null);

    expect(screen.queryByRole('button', { name: /Show more/u })).toBeNull();
  });
});

describe('loading the next page', () => {
  it('appends it, in the order the service sent it', async () => {
    readMock.mockResolvedValue({
      items: [card({ id: 'p2', slug: 'second', title: 'A second campaign' })],
      nextCursor: null,
    });

    renderList('cursor-1');
    await userEvent.click(screen.getByRole('button', { name: 'Show more campaigns in Spring 2026' }));

    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'A second campaign' })).toBeInTheDocument();
    });
    expect(screen.getByRole('link', { name: 'A campaign' })).toBeInTheDocument();
    expect(readMock).toHaveBeenCalledWith('spring-2026', 'cursor-1');
  });

  it('stops offering more once the cursor runs out', async () => {
    readMock.mockResolvedValue({ items: [card({ id: 'p2', slug: 'second' })], nextCursor: null });

    renderList('cursor-1');
    await userEvent.click(screen.getByRole('button', { name: /Show more/u }));

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /Show more/u })).toBeNull();
    });
  });

  it('keeps the pages already read when the next one is refused', async () => {
    readMock.mockRejectedValue(
      new ApiError(503, { title: 'Service unavailable', detail: 'Try again shortly.' }, 'refused'),
    );

    renderList('cursor-1');
    await userEvent.click(screen.getByRole('button', { name: /Show more/u }));

    await waitFor(() => {
      expect(screen.getByText('Try again shortly.')).toBeInTheDocument();
    });
    expect(screen.getByRole('link', { name: 'A campaign' })).toBeInTheDocument();
    // Still offered, because the cursor did not advance: the page can be asked for again.
    expect(screen.getByRole('button', { name: /Show more/u })).toBeInTheDocument();
  });
});

describe('with nothing published in the collection', () => {
  it('says so rather than telling the reader to clear a filter they never applied', () => {
    renderList(null, []);

    expect(
      screen.getByRole('heading', { level: 2, name: 'Nothing to show here yet' }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/clear/iu)).toBeNull();
    expect(screen.getByRole('link', { name: 'Browse the feed' })).toHaveAttribute(
      'href',
      '/discover',
    );
  });
});
