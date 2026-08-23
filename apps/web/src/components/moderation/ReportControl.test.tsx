import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { submitReport } from '../../lib/moderation/report';
import { fetchSession, type Session } from '../../lib/session/session';
import { SessionProvider } from '../session/SessionProvider';
import { ReportControl } from './ReportControl';

/**
 * §4.9's C-06 and C-07 — issue #286.
 *
 * WHAT THESE COVER:
 *
 *   - **a visitor with no session is offered a sign-in, not a form.** All three endpoints
 *     require a bearer token, and a dialog that collected a complaint and then lost it at the
 *     last step is worse than one that says so first.
 *   - the dialog is a dialog: `role`, `aria-modal`, and an accessible name that says what is
 *     being reported.
 *   - `OTHER` will not submit without a sentence, because it is the one reason a moderator
 *     cannot act on without one.
 *   - **the acknowledgement does not claim anything happened to the target.** A report is a
 *     request for a person to look, not a vote, and saying otherwise would invite five
 *     accounts to try removing a campaign between them.
 */

vi.mock('../../lib/moderation/report', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/moderation/report')>()),
  submitReport: vi.fn(),
}));
vi.mock('../../lib/session/session', () => ({ fetchSession: vi.fn() }));
vi.mock('../../lib/api/access-token', () => ({ signOut: vi.fn().mockResolvedValue(undefined) }));
vi.mock('next/navigation', () => ({
  usePathname: () => '/projects/aysel/a-game',
  useRouter: () => ({
    push: () => {},
    replace: () => {},
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => {},
  }),
}));

const submitMock = vi.mocked(submitReport);
const sessionMock = vi.mocked(fetchSession);

const ACCOUNT: Session = {
  id: 'u1',
  email: 'aysel@example.com',
  name: 'Aysel',
  slug: 'aysel',
  emailVerified: true,
};

function renderControl() {
  return render(
    <SessionProvider>
      <ReportControl
        target={{ kind: 'campaign', id: 'p1' }}
        name="A tabletop game"
        returnTo="/projects/aysel/a-game"
      />
    </SessionProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  sessionMock.mockResolvedValue(ACCOUNT);
  submitMock.mockResolvedValue({
    id: 'report-1',
    target: { type: 'PROJECT', id: 'p1' },
    reason: 'SPAM',
    state: 'OPEN',
    createdAt: '2026-08-23T09:00:00Z',
  });
});

afterEach(cleanup);

describe('the trigger', () => {
  it('names what it reports rather than saying only “Report”', async () => {
    renderControl();
    expect(
      await screen.findByRole('button', { name: 'Report this campaign' }),
    ).toBeInTheDocument();
  });
});

describe('the dialog', () => {
  it('is a modal dialog with a name that says what is being reported', async () => {
    const user = userEvent.setup();
    renderControl();

    await user.click(await screen.findByRole('button', { name: 'Report this campaign' }));

    const dialog = screen.getByRole('dialog', { name: 'Report A tabletop game' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
  });

  it('sends the chosen reason', async () => {
    const user = userEvent.setup();
    renderControl();

    await user.click(await screen.findByRole('button', { name: 'Report this campaign' }));
    await user.click(screen.getByRole('radio', { name: /^Fraud/u }));
    await user.click(screen.getByRole('button', { name: 'Send report' }));

    expect(submitMock).toHaveBeenCalledWith({ kind: 'campaign', id: 'p1' }, 'FRAUD', '');
  });

  it('will not send “Other” without a sentence a moderator can act on', async () => {
    const user = userEvent.setup();
    renderControl();

    await user.click(await screen.findByRole('button', { name: 'Report this campaign' }));
    await user.click(screen.getByRole('radio', { name: /^Other/u }));
    await user.click(screen.getByRole('button', { name: 'Send report' }));

    expect(screen.getByText(/needs a sentence/u)).toBeInTheDocument();
    expect(submitMock).not.toHaveBeenCalled();
  });

  it('acknowledges without claiming anything happened to the campaign', async () => {
    const user = userEvent.setup();
    renderControl();

    await user.click(await screen.findByRole('button', { name: 'Report this campaign' }));
    await user.click(screen.getByRole('radio', { name: /^Spam/u }));
    await user.click(screen.getByRole('button', { name: 'Send report' }));

    expect(await screen.findByText('A moderator will look at this')).toBeInTheDocument();
    expect(screen.getByText(/not a vote/u)).toBeInTheDocument();
  });

  it('prints the service’s refusal, including the rate limit', async () => {
    submitMock.mockRejectedValue(
      new ApiError(429, { detail: 'You have reported a few things recently.' }),
    );
    const user = userEvent.setup();
    renderControl();

    await user.click(await screen.findByRole('button', { name: 'Report this campaign' }));
    await user.click(screen.getByRole('radio', { name: /^Spam/u }));
    await user.click(screen.getByRole('button', { name: 'Send report' }));

    expect(await screen.findByText('You have reported a few things recently.')).toBeInTheDocument();
  });
});

describe('a visitor with no session', () => {
  beforeEach(() => sessionMock.mockResolvedValue(null));

  it('is offered a sign-in that returns them here, and no form', async () => {
    const user = userEvent.setup();
    renderControl();

    await user.click(await screen.findByRole('button', { name: 'Report this campaign' }));

    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/sign-in?next=%2Fprojects%2Faysel%2Fa-game',
    );
    expect(screen.queryByRole('button', { name: 'Send report' })).not.toBeInTheDocument();
  });
});
