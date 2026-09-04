import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { listTickets, readTicketQueue, type Ticket, type TicketPage } from '../../lib/admin/tickets';
import { listUsers, type AdminUser } from '../../lib/admin/api';
import { SupportConsole } from './SupportConsole';
import { translatorFor } from '../../test-copy';
import { consoleChromeCopyFrom } from '../../lib/i18n/admin/common-copy';
import { supportConsoleCopyFrom } from '../../lib/i18n/admin/people-copy';

/*
 * Copy built from `messages/en.json` through the same builder the route calls — see
 * `src/test-copy.ts` for why a suite that retyped the sentences would stay green with the
 * catalogue empty.
 */
const ADMIN = translatorFor('admin');
const COPY = supportConsoleCopyFrom(ADMIN, consoleChromeCopyFrom(ADMIN, translatorFor('common')));

vi.mock('../../lib/admin/tickets', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/admin/tickets')>();
  return { ...actual, readTicketQueue: vi.fn(), listTickets: vi.fn(), readTicket: vi.fn() };
});

/* #414's picker searches the account directory, which is a different module and an audited
   read. Mocked here for the same reason the ticket store is: this suite is about which filter
   reaches the service, not about how the directory answers. */
vi.mock('../../lib/admin/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/admin/api')>();
  return { ...actual, listUsers: vi.fn() };
});

const readTicketQueueMock = vi.mocked(readTicketQueue);
const listTicketsMock = vi.mocked(listTickets);
const listUsersMock = vi.mocked(listUsers);

const COLLEAGUE: AdminUser = {
  id: 'cccccccc-0000-4000-8000-000000000001',
  email: 'ayan@ideanest.test',
  name: 'Ayan Quliyeva',
  slug: 'ayan-quliyeva',
  emailVerified: true,
  emailVerifiedAt: '2026-01-04T09:00:00.000Z',
  suspended: false,
  suspendedAt: null,
  suspendedBy: null,
  suspensionReason: null,
  deletionScheduledAt: null,
  createdAt: '2026-01-04T09:00:00.000Z',
};

/** Types a name into the picker, searches, and picks whoever came back. */
async function pickColleague(): Promise<void> {
  await userEvent.type(
    screen.getByLabelText(COPY.assigneePicker.label),
    COLLEAGUE.name,
  );
  await userEvent.click(screen.getByRole('button', { name: COPY.assigneePicker.search }));
  await userEvent.click(await screen.findByRole('button', { name: new RegExp(COLLEAGUE.name) }));
}

const TICKET: Ticket = {
  id: 'aaaaaaaa-0000-4000-8000-000000000001',
  requesterId: 'bbbbbbbb-0000-4000-8000-000000000001',
  subject: 'My card was refused four times',
  subjectType: 'PLEDGE',
  subjectRef: null,
  state: 'OPEN',
  priority: 'URGENT',
  assigneeId: null,
  createdAt: '2026-08-24T09:00:00.000Z',
  updatedAt: '2026-08-24T09:00:00.000Z',
  resolvedAt: null,
};

function page(overrides: Partial<TicketPage> = {}): TicketPage {
  return { tickets: [TICKET], page: 0, hasMore: false, ...overrides };
}

beforeEach(() => {
  vi.resetAllMocks();
  readTicketQueueMock.mockResolvedValue(page());
  listTicketsMock.mockResolvedValue(page());
  listUsersMock.mockResolvedValue({ users: [COLLEAGUE], nextCursor: null });
});

describe('the support console — issue #404', () => {
  it('opens on the queue, which is a different question from the list', async () => {
    render(<SupportConsole copy={COPY} />);

    // `readTicketQueue` is open work, most urgent first; the list is everything, newest
    // first. Widening the queue with filters would have left a queue whose order no longer
    // means "work this from the front".
    expect(await screen.findByText('My card was refused four times')).toBeInTheDocument();
    expect(readTicketQueueMock).toHaveBeenCalled();
    expect(listTicketsMock).not.toHaveBeenCalled();
  });

  it('sends a state to the service instead of narrowing the page it holds', async () => {
    render(<SupportConsole copy={COPY} />);
    await screen.findByText('My card was refused four times');

    await userEvent.click(screen.getByRole('button', { name: COPY.state.RESOLVED }));

    await waitFor(() =>
      expect(listTicketsMock).toHaveBeenCalledWith(
        expect.objectContaining({ state: 'RESOLVED' }),
        0,
        expect.anything(),
      ),
    );
  });

  it('combines a priority with a state, because they are separate questions', async () => {
    render(<SupportConsole copy={COPY} />);
    await screen.findByText('My card was refused four times');

    await userEvent.click(screen.getByRole('button', { name: COPY.state.OPEN }));
    await waitFor(() => expect(listTicketsMock).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: COPY.priority.URGENT }));

    // "Urgent things that are still open" is one chip from each row, which is how an
    // operator actually asks the question.
    await waitFor(() =>
      expect(listTicketsMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ state: 'OPEN', priority: 'URGENT' }),
        0,
        expect.anything(),
      ),
    );
  });

  it('filters to what nobody has picked up, which is not the same as any assignee', async () => {
    render(<SupportConsole copy={COPY} />);
    await screen.findByText('My card was refused four times');

    await userEvent.click(screen.getByRole('button', { name: COPY.unassignedOnly }));

    // A null assignee already means "anybody's", so "nobody's" needs a parameter of its own.
    await waitFor(() =>
      expect(listTicketsMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ unassigned: true }),
        0,
        expect.anything(),
      ),
    );
  });

  it('narrows to one colleague, which the endpoint has accepted since #404 — issue #414', async () => {
    render(<SupportConsole copy={COPY} />);
    await screen.findByText('My card was refused four times');

    await pickColleague();

    /*
     * "What is on their plate" was reachable only by editing the URL: `?assigneeId=` has been
     * accepted since #404 and the third chip row was two chips wide — "Anyone" and "Nobody
     * yet" — which answers only "what has nobody picked up".
     */
    await waitFor(() =>
      expect(listTicketsMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ assigneeId: COLLEAGUE.id, unassigned: false }),
        0,
        expect.anything(),
      ),
    );
  });

  it('does not let a named assignee and "nobody yet" be asked for together', async () => {
    render(<SupportConsole copy={COPY} />);
    await screen.findByText('My card was refused four times');

    await pickColleague();
    await waitFor(() => expect(listTicketsMock).toHaveBeenCalled());

    await userEvent.click(screen.getByRole('button', { name: COPY.unassignedOnly }));

    /*
     * The two contradict each other: a null assignee already means "anybody's" and one value
     * cannot also mean "nobody's". The service answers the contradiction with nothing, which is
     * correct and is not something a screen should let a reader stumble into — so the later
     * choice clears the earlier one rather than being sent alongside it.
     */
    await waitFor(() =>
      expect(listTicketsMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ assigneeId: null, unassigned: true }),
        0,
        expect.anything(),
      ),
    );
  });

  it('clears both halves of the assignment filter when the reader asks for anyone', async () => {
    render(<SupportConsole copy={COPY} />);
    await screen.findByText('My card was refused four times');

    await pickColleague();
    await waitFor(() => expect(listTicketsMock).toHaveBeenCalled());
    readTicketQueueMock.mockClear();

    await userEvent.click(screen.getByRole('button', { name: COPY.anyAssignee }));

    // Nothing left narrowing the list, so this is the queue again rather than the list with
    // nothing selected — the distinction the screen has drawn since #404.
    await waitFor(() => expect(readTicketQueueMock).toHaveBeenCalled());
  });

  it('goes back to the queue, and stops asking the list', async () => {
    render(<SupportConsole copy={COPY} />);
    await screen.findByText('My card was refused four times');

    await userEvent.click(screen.getByRole('button', { name: COPY.state.CLOSED }));
    await waitFor(() => expect(listTicketsMock).toHaveBeenCalled());
    readTicketQueueMock.mockClear();

    await userEvent.click(screen.getByRole('button', { name: COPY.queueOnly }));

    await waitFor(() => expect(readTicketQueueMock).toHaveBeenCalled());
  });

  it('says the badge is a page rather than the population — #404’s honest count', async () => {
    readTicketQueueMock.mockResolvedValue(page({ hasMore: true }));

    render(<SupportConsole copy={COPY} />);
    await screen.findByText('My card was refused four times');

    /*
     * This badge printed the length of the loaded page as though it were the total, which
     * is the defect `ConsoleCount` exists for. "There are more" now comes from `hasMore` —
     * the same fact the pager is built on, so the two cannot disagree.
     */
    expect(screen.getByText('1+')).toBeInTheDocument();
  });

  it('says nothing matched rather than that there are no tickets', async () => {
    render(<SupportConsole copy={COPY} />);
    await screen.findByText('My card was refused four times');

    listTicketsMock.mockResolvedValue(page({ tickets: [] }));
    await userEvent.click(screen.getByRole('button', { name: COPY.state.CLOSED }));

    expect(await screen.findByText(COPY.filteredTitle)).toBeInTheDocument();
    expect(screen.queryByText(COPY.emptyTitle)).not.toBeInTheDocument();
  });
});
