import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import {
  listNotifications,
  markNotificationRead,
  type InboxNotification,
  type InboxPage,
} from '../../lib/notifications/api';
import { InboxPanel } from './InboxPanel';

vi.mock('../../lib/notifications/api', () => ({
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}));

const listMock = vi.mocked(listNotifications);
const markReadMock = vi.mocked(markNotificationRead);

const NAMED = JSON.stringify({
  projectTitle: 'Xari Bulbul Ceramics',
  creatorSlug: 'aysel-studio',
  projectSlug: 'xari-bulbul-ceramics',
  goal: { amount: '5000.00', currency: 'AZN' },
  total: { amount: '120.00', currency: 'AZN' },
});

function notification(
  overrides: Partial<InboxNotification> & Pick<InboxNotification, 'id'>,
): InboxNotification {
  return {
    type: 'GOAL_REACHED',
    category: 'CAMPAIGN',
    params: NAMED,
    occurredAt: '2026-08-19T09:00:00.000Z',
    ...overrides,
  };
}

function page(overrides: Partial<InboxPage> = {}): InboxPage {
  return { notifications: [], unreadCount: 0, ...overrides };
}

const GOAL = notification({ id: 'goal' });
const PLEDGE = notification({
  id: 'pledge',
  type: 'PLEDGE_CONFIRMED',
  category: 'PLEDGES',
  readAt: '2026-08-19T10:00:00.000Z',
});

function rows(): HTMLElement[] {
  return screen.getAllByRole('listitem');
}

beforeEach(() => {
  vi.clearAllMocks();
});

/**
 * Appearance is reviewed in Storybook. These cover BEHAVIOUR and ACCESSIBILITY — the
 * wiring that breaks silently and still ships.
 */
describe('InboxPanel', () => {
  it('announces that it is loading rather than showing a blank panel', () => {
    listMock.mockReturnValue(new Promise<InboxPage>(() => {}));
    render(<InboxPanel />);

    // The placeholders themselves are `aria-hidden`; the container carries the message and
    // the busy state, so a screen reader hears the wait rather than unnamed rectangles.
    const label = screen.getByText('Loading your notifications');
    expect(label.closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true');
  });

  it('renders each notification as a sentence naming its campaign', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL, PLEDGE], unreadCount: 1 }));
    render(<InboxPanel />);

    await waitFor(() => expect(rows()).toHaveLength(2));
    expect(
      screen.getByText('Xari Bulbul Ceramics reached its goal of 5,000.00 AZN'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('Your pledge of 120.00 AZN to Xari Bulbul Ceramics is confirmed'),
    ).toBeInTheDocument();
  });

  /*
   * CLAUDE.md §2: colour alone must never carry meaning. "Unread" is exactly the state a
   * coloured dot is usually asked to carry on its own.
   */
  it('says "unread" in words as well as marking it', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL, PLEDGE], unreadCount: 1 }));
    render(<InboxPanel />);

    await waitFor(() => expect(rows()).toHaveLength(2));
    expect(within(rows()[0] as HTMLElement).getByText('Unread')).toBeInTheDocument();
    expect(within(rows()[1] as HTMLElement).queryByText('Unread')).not.toBeInTheDocument();
  });

  it('shows the service badge number rather than a count of the loaded page', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL], unreadCount: 42 }));
    render(<InboxPanel />);

    expect(await screen.findByText('42 unread')).toBeInTheDocument();
  });

  it('links a campaign notification to the two-segment public page', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL], unreadCount: 1 }));
    render(<InboxPanel />);

    const link = await screen.findByRole('link', {
      name: 'Xari Bulbul Ceramics reached its goal of 5,000.00 AZN',
    });
    expect(link).toHaveAttribute('href', '/en/projects/aysel-studio/xari-bulbul-ceramics');
  });

  /*
   * A row whose document names no campaign has nowhere useful to go. Plain text rather
   * than a live-looking link: unlike an email, the reader is already inside the
   * application, so a link back to the home page is not a way back to anything.
   */
  it('renders a row with no destination as text rather than as a link', async () => {
    listMock.mockResolvedValue(
      page({ notifications: [notification({ id: 'bare', params: '{}' })], unreadCount: 1 }),
    );
    render(<InboxPanel />);

    await waitFor(() => expect(rows()).toHaveLength(1));
    expect(screen.getByText('A campaign reached its goal of what it needed')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('marks one read and decrements the badge without re-fetching', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL], unreadCount: 3 }));
    markReadMock.mockResolvedValue({ ...GOAL, readAt: '2026-08-20T09:00:00.000Z' });
    render(<InboxPanel />);

    await userEvent.click(await screen.findByRole('button', { name: 'Mark as read' }));

    await waitFor(() => expect(screen.getByText('2 unread')).toBeInTheDocument());
    expect(markReadMock).toHaveBeenCalledWith('goal');
    expect(listMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: 'Mark as read' })).not.toBeInTheDocument();
  });

  it('reports a refusal to mark read rather than showing the row as read', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL], unreadCount: 1 }));
    markReadMock.mockRejectedValue(new ApiError(404, { title: 'Not found' }, 'Not found'));
    render(<InboxPanel />);

    await userEvent.click(await screen.findByRole('button', { name: 'Mark as read' }));

    expect(await screen.findByText('Not found')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark as read' })).toBeInTheDocument();
  });

  it('filters the loaded rows by category', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL, PLEDGE], unreadCount: 1 }));
    render(<InboxPanel />);

    await waitFor(() => expect(rows()).toHaveLength(2));
    await userEvent.click(screen.getByRole('button', { name: /Your pledges/ }));

    expect(rows()).toHaveLength(1);
    expect(
      screen.getByText('Your pledge of 120.00 AZN to Xari Bulbul Ceramics is confirmed'),
    ).toBeInTheDocument();
  });

  it('filters to unread only', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL, PLEDGE], unreadCount: 1 }));
    render(<InboxPanel />);

    await waitFor(() => expect(rows()).toHaveLength(2));
    const toggle = screen.getByRole('button', { name: 'Unread only' });
    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(rows()).toHaveLength(1);
  });

  /*
   * The endpoint takes a cursor and nothing else, so these filters are applied in the
   * browser to what has already been fetched. "No results" therefore means "none in what
   * has loaded" — and saying otherwise would be the difference between a filter and a lie.
   */
  it('says an empty filter result is about what has loaded, not about the inbox', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL], unreadCount: 1 }));
    render(<InboxPanel />);

    await waitFor(() => expect(rows()).toHaveLength(1));
    await userEvent.click(screen.getByRole('button', { name: /Comments and messages/ }));

    expect(screen.getByText('Nothing here in what has loaded')).toBeInTheDocument();
    expect(screen.getByText(/Load more to keep looking/)).toBeInTheDocument();
  });

  it('says an empty inbox is empty', async () => {
    listMock.mockResolvedValue(page());
    render(<InboxPanel />);

    expect(await screen.findByText('Nothing yet')).toBeInTheDocument();
  });

  it('continues from the cursor the service returned, and sends both halves', async () => {
    listMock
      .mockResolvedValueOnce(
        page({
          notifications: [GOAL],
          nextCursor: '2026-08-19T09:00:00.000Z',
          nextCursorId: 'goal',
          unreadCount: 2,
        }),
      )
      .mockResolvedValueOnce(page({ notifications: [PLEDGE], unreadCount: 2 }));
    render(<InboxPanel />);

    await userEvent.click(await screen.findByRole('button', { name: 'Load more' }));

    await waitFor(() => expect(rows()).toHaveLength(2));
    expect(listMock).toHaveBeenLastCalledWith({
      before: '2026-08-19T09:00:00.000Z',
      beforeId: 'goal',
    });
    // The last page carries no cursor, so there is nothing left to ask for.
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('offers no "load more" when the first page is the whole inbox', async () => {
    listMock.mockResolvedValue(page({ notifications: [GOAL], unreadCount: 1 }));
    render(<InboxPanel />);

    await waitFor(() => expect(rows()).toHaveLength(1));
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('offers a retry when the read fails, and recovers', async () => {
    listMock
      .mockRejectedValueOnce(new ApiError(503, null, 'Service unavailable'))
      .mockResolvedValueOnce(page({ notifications: [GOAL], unreadCount: 1 }));
    render(<InboxPanel />);

    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(rows()).toHaveLength(1));
  });

  it('says so plainly when the session has gone rather than reporting an error', async () => {
    listMock.mockRejectedValue(new ApiError(401, null, 'Not signed in'));
    render(<InboxPanel />);

    expect(await screen.findByText('You are signed out')).toBeInTheDocument();
  });

  it('groups rows under a day heading', async () => {
    listMock.mockResolvedValue(
      page({
        notifications: [
          notification({ id: 'today', occurredAt: new Date().toISOString() }),
          notification({ id: 'older', occurredAt: '2026-01-02T09:00:00.000Z' }),
        ],
        unreadCount: 2,
      }),
    );
    render(<InboxPanel />);

    expect(await screen.findByRole('heading', { name: 'Today', level: 3 })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(2);
  });
});
