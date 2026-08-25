import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type {
  CampaignComment,
  CampaignCommentPage,
  CampaignCommentThread,
} from '../../lib/community/comments';
import { deleteComment, postComment, replyToComment } from '../../lib/community/comments';
import { fetchSession, type Session } from '../../lib/session/session';
import { SessionProvider } from '../session/SessionProvider';
import { CampaignComments } from './CampaignComments';

/**
 * §4.4's Comments tab — #285, over §4.9's C-01, C-02, C-03 and C-07.
 *
 * WHAT THESE COVER:
 *
 *   - **a withdrawn comment is a tombstone in place, and names nobody.** §4.9 keeps the row so
 *     replies are not orphaned and so a moderator holding a report can still read what it
 *     said, and refuses to print "removed" beside a name because that is an accusation
 *     published to everybody on the campaign page.
 *   - **the creator highlight is a word, not a tint** (docs/ui-kit.md §9.2). "This answer is
 *     from the person asking for the money" is exactly the meaning that must not depend on
 *     being able to see a border.
 *   - **the reply control follows `acceptsReplies`**, which §4.9 puts on every row precisely
 *     so a client places it rather than discovering the two-level bound by being refused.
 *   - **withdrawal is offered to the author and to nobody else**, and it says what it actually
 *     does — the row survives and cannot be edited or restored.
 *   - **a signed-out reader gets a sign-in that returns here, never a box that collects a
 *     paragraph and loses it at the last step.**
 *   - **the body is text, never markup.** A comment is a stranger's text arriving from a
 *     public endpoint on the origin that holds the session cookie.
 */

vi.mock('../../lib/community/comments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/community/comments')>()),
  postComment: vi.fn(),
  replyToComment: vi.fn(),
  deleteComment: vi.fn(),
}));
vi.mock('../../lib/session/session', () => ({ fetchSession: vi.fn() }));
vi.mock('../../lib/api/access-token', () => ({ signOut: vi.fn().mockResolvedValue(undefined) }));

const refresh = vi.fn();
vi.mock('next/navigation', async (importOriginal) => ({
  /*
   * Spread first so the real module's other exports survive. `i18n/navigation.ts`
   * builds its wrappers at import time and reads `redirect` and `permanentRedirect`
   * while doing so, and a factory that replaced the module wholesale left those
   * undefined — which failed as a TypeError inside next-intl rather than anywhere
   * near the test that caused it.
   */
  ...(await importOriginal<typeof import('next/navigation')>()),
  usePathname: () => '/projects/ayan/coffee-table-book',
  useRouter: () => ({
    push: () => {},
    replace: () => {},
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => refresh(),
  }),
}));

const postMock = vi.mocked(postComment);
const replyMock = vi.mocked(replyToComment);
const deleteMock = vi.mocked(deleteComment);
const sessionMock = vi.mocked(fetchSession);

const PATH = '/projects/ayan/coffee-table-book';

const ACCOUNT: Session = {
  id: 'u1',
  email: 'ayan@example.com',
  name: 'Ayan Q',
  slug: 'ayan',
  emailVerified: true,
};

function comment(overrides: Partial<CampaignComment> = {}): CampaignComment {
  return {
    id: 'c1',
    threadId: 'c1',
    parentId: null,
    authorId: 'u9',
    body: 'Will this ship to Georgia?',
    byCreator: false,
    deleted: false,
    depth: 0,
    createdAt: '2026-08-01T10:00:00Z',
    acceptsReplies: true,
    ...overrides,
  };
}

function thread(overrides: Partial<CampaignCommentThread> = {}): CampaignCommentThread {
  return { root: comment(), replies: [], nextReplyCursor: null, ...overrides };
}

function page(threads: readonly CampaignCommentThread[]): CampaignCommentPage {
  return { threads, nextCursor: null };
}

function renderTab(
  body: CampaignCommentPage | null,
  overrides: Partial<React.ComponentProps<typeof CampaignComments>> = {},
) {
  return render(
    <SessionProvider>
      <CampaignComments
        page={body}
        projectId="p1"
        campaignTitle="A coffee table book"
        returnTo={PATH}
        olderHref={null}
        singleThread={false}
        allThreadsHref={`${PATH}?tab=comments`}
        threadHrefs={{}}
        {...overrides}
      />
    </SessionProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  sessionMock.mockResolvedValue(ACCOUNT);
  postMock.mockResolvedValue(null);
  replyMock.mockResolvedValue(null);
  deleteMock.mockResolvedValue(undefined);
});

afterEach(cleanup);

describe('the comments tab', () => {
  it('puts the conversation in the markup rather than in a request', async () => {
    renderTab(page([thread()]));

    expect(await screen.findByText('Will this ship to Georgia?')).toBeInTheDocument();
  });

  it('marks the campaign’s own reply in words rather than only in colour', async () => {
    renderTab(
      page([
        thread({
          replies: [
            comment({
              id: 'c2',
              parentId: 'c1',
              depth: 1,
              byCreator: true,
              body: 'Yes, Georgia is in zone 1.',
              acceptsReplies: false,
            }),
          ],
        }),
      ]),
    );

    expect(await screen.findByText('From the campaign')).toBeInTheDocument();
    expect(screen.getByText('Yes, Georgia is in zone 1.')).toBeInTheDocument();
  });

  it('renders a withdrawn comment as a tombstone that names nobody', async () => {
    const { container } = renderTab(
      page([
        thread({
          root: comment({ deleted: true, body: null, authorId: null, acceptsReplies: false }),
          replies: [comment({ id: 'c2', parentId: 'c1', depth: 1, body: 'Still here.' })],
        }),
      ]),
    );

    expect(await screen.findByText('This comment was withdrawn.')).toBeInTheDocument();
    // The reply survives, which is the whole reason §4.9 keeps the row.
    expect(screen.getByText('Still here.')).toBeInTheDocument();
    // No name, and nothing that says who removed it.
    expect(container.textContent).not.toMatch(/removed by|deleted by/iu);
  });

  it('offers no controls under a tombstone', async () => {
    renderTab(
      page([
        thread({ root: comment({ deleted: true, body: null, authorId: null, acceptsReplies: true }) }),
      ]),
    );

    await screen.findByText('This comment was withdrawn.');
    expect(screen.queryByRole('button', { name: 'Reply' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Report/u })).not.toBeInTheDocument();
  });

  it('renders a comment body as text, never as markup', async () => {
    renderTab(page([thread({ root: comment({ body: '<script>alert(1)</script>' }) })]));

    expect(await screen.findByText('<script>alert(1)</script>')).toBeInTheDocument();
  });

  it('blames the service, not the campaign, when the read was refused', () => {
    renderTab(null);

    expect(screen.getByText(/could not be loaded/u)).toBeInTheDocument();
    expect(screen.queryByText(/Nobody has commented/u)).not.toBeInTheDocument();
  });

  it('says nobody has commented only when nobody has', () => {
    renderTab(page([]));

    expect(screen.getByText('Nobody has commented on this campaign yet.')).toBeInTheDocument();
  });

  it('offers the rest of a long conversation as a link rather than as a press', async () => {
    renderTab(
      page([thread({ nextReplyCursor: 'c50' })]),
      { threadHrefs: { c1: `${PATH}?tab=comments&thread=c1` } },
    );

    expect(await screen.findByRole('link', { name: 'Show more replies' })).toHaveAttribute(
      'href',
      `/en${PATH}?tab=comments&thread=c1`,
    );
  });
});

describe('the reply control', () => {
  it('is placed by acceptsReplies rather than by the depth', async () => {
    renderTab(
      page([
        thread({
          root: comment({ acceptsReplies: false }),
          replies: [comment({ id: 'c2', depth: 1, acceptsReplies: false, body: 'Me too.' })],
        }),
      ]),
    );

    await screen.findByText('Will this ship to Georgia?');
    expect(screen.queryByRole('button', { name: 'Reply' })).not.toBeInTheDocument();
  });

  it('posts an answer to the comment it was opened under', async () => {
    renderTab(page([thread()]));

    await userEvent.click(await screen.findByRole('button', { name: 'Reply' }));
    await userEvent.type(screen.getByLabelText('Your reply'), 'Yes, it ships.');
    await userEvent.click(screen.getByRole('button', { name: 'Post reply' }));

    await waitFor(() => expect(replyMock).toHaveBeenCalledWith('c1', 'Yes, it ships.'));
    // The list is re-read rather than patched, so the server decides the nesting and the
    // creator highlight — see `CommentComposer` for why that is not an optimisation to make.
    expect(refresh).toHaveBeenCalled();
  });
});

describe('writing a comment', () => {
  it('posts to the campaign and asks the server to render the list again', async () => {
    renderTab(page([]));

    await userEvent.type(await screen.findByLabelText('Add a comment'), 'When does it ship?');
    await userEvent.click(screen.getByRole('button', { name: 'Post comment' }));

    await waitFor(() => expect(postMock).toHaveBeenCalledWith('p1', 'When does it ship?'));
    expect(refresh).toHaveBeenCalled();
  });

  it('refuses an empty body without spending a request', async () => {
    renderTab(page([]));

    await userEvent.click(await screen.findByRole('button', { name: 'Post comment' }));

    expect(postMock).not.toHaveBeenCalled();
    expect(screen.getByText('Write something first.')).toBeInTheDocument();
  });

  it('offers a signed-out reader a sign-in that returns here, not a form', async () => {
    sessionMock.mockResolvedValue(null);
    renderTab(page([]));

    const link = await screen.findByRole('link', { name: 'Sign in to comment' });
    expect(link).toHaveAttribute('href', `/en/sign-in?next=${encodeURIComponent(PATH)}`);
    expect(screen.queryByLabelText('Add a comment')).not.toBeInTheDocument();
  });
});

describe('withdrawing a comment', () => {
  it('is offered to the author and to nobody else', async () => {
    renderTab(page([thread({ root: comment({ authorId: 'u9' }) })]));

    await screen.findByText('Will this ship to Georgia?');
    expect(screen.queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument();

    cleanup();
    renderTab(page([thread({ root: comment({ authorId: ACCOUNT.id }) })]));

    expect(await screen.findByRole('button', { name: 'Withdraw' })).toBeInTheDocument();
  });

  /**
   * §4.9 is explicit that this is not a removal. Somebody pressing it to make a sentence
   * disappear from the internet is entitled to know that it will still be there, marked as
   * withdrawn, for the moderator holding a report about it.
   */
  it('says what it actually does before it does it', async () => {
    renderTab(page([thread({ root: comment({ authorId: ACCOUNT.id }) })]));

    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw' }));

    expect(screen.getByText(/leaves a note saying the comment was removed/u)).toBeInTheDocument();
    expect(screen.getByText(/cannot be edited or restored/u)).toBeInTheDocument();
    expect(deleteMock).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Withdraw it' }));

    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith('c1'));
    expect(refresh).toHaveBeenCalled();
  });
});
