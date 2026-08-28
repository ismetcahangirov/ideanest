import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { requestEmailChange } from '../../lib/auth/credentials';
import { EmailChangePanel } from './EmailChangePanel';
import { emailChangePanelCopyFrom } from '../../lib/i18n/settings-copy';
import { translatorFor } from '../../test-copy';

/*
 * The copy the page would have resolved, built from `messages/en.json` by the same function it
 * calls — issue #324. Retyping the sentences here would give a test that passes whatever the
 * catalogue says, which is the opposite of what it is for.
 */
const COPY = emailChangePanelCopyFrom(translatorFor('settings.panels'), translatorFor('auth'));

/**
 * §4.1's A-12 — issue #277.
 *
 * WHAT THESE COVER:
 *
 *   - **the account has not moved, and the screen says so after a success rather than before
 *     it.** `POST /v1/auth/change-email` answers 202 and `users.email` moves only when the new
 *     address follows its link. "Your email address is now …" would be false for as long as the
 *     link is unopened — which may be for ever — and false to the one person who then cannot
 *     find their account.
 *   - **both addresses are written to, and the screen says which message goes where.** The old
 *     address gets a notice with no link: it cannot approve anything, and it is there so a
 *     change somebody did not make reaches them while the account is still theirs.
 *   - **nothing warns about a sign-out**, because an address change revokes no sessions. A
 *     warning about a consequence that does not happen is the same defect as silence about one
 *     that does.
 *   - a wrong password and a taken address each go under the field they are about.
 */

vi.mock('../session/SessionProvider', () => ({
  useSession: () => ({
    status: 'signed-in',
    session: {
      id: 'u1',
      email: 'aysel@example.com',
      name: 'Aysel',
      slug: 'aysel',
      emailVerified: true,
    },
    refresh: vi.fn(),
    signOut: vi.fn(),
  }),
}));

vi.mock('../../lib/auth/credentials', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/auth/credentials')>()),
  requestEmailChange: vi.fn(),
}));

const requestMock = vi.mocked(requestEmailChange);

beforeEach(() => {
  requestMock.mockReset();
  requestMock.mockResolvedValue(undefined);
});

afterEach(cleanup);

async function ask(
  user: ReturnType<typeof userEvent.setup>,
  address = ' new@example.com ',
  password = 'mine',
): Promise<void> {
  await user.type(screen.getByLabelText(/Current password/u), password);
  await user.type(screen.getByLabelText(/New email address/u), address);
  await user.click(screen.getByRole('button', { name: 'Send the confirmation' }));
}

describe('the form', () => {
  it('shows the address currently on the account', () => {
    render(<EmailChangePanel copy={COPY} />);

    expect(screen.getByText('aysel@example.com')).toBeInTheDocument();
  });

  it('warns about nothing being signed out, because nothing is', () => {
    render(<EmailChangePanel copy={COPY} />);

    expect(screen.queryByText(/sign(s|ed) you out/iu)).not.toBeInTheDocument();
  });

  it('sends the password and the trimmed address', async () => {
    const user = userEvent.setup();
    render(<EmailChangePanel copy={COPY} />);

    await ask(user);

    expect(requestMock).toHaveBeenCalledWith({
      currentPassword: 'mine',
      newEmail: 'new@example.com',
    });
  });
});

describe('after the request is accepted', () => {
  it('says nothing has changed yet, and which address still signs in', async () => {
    const user = userEvent.setup();
    render(<EmailChangePanel copy={COPY} />);

    await ask(user);

    expect(await screen.findByText(/Nothing has changed yet/u)).toBeInTheDocument();
    expect(screen.getByText(/You still sign in with aysel@example.com/u)).toBeInTheDocument();
    // Never the sentence that is false until a link is opened.
    expect(screen.queryByText(/is now new@example.com/u)).not.toBeInTheDocument();
  });

  it('says the link went to the new address and the notice to the old one', async () => {
    const user = userEvent.setup();
    render(<EmailChangePanel copy={COPY} />);

    await ask(user);

    expect(await screen.findByText('new@example.com')).toBeInTheDocument();
    expect(screen.getByText(/carries no link/u)).toBeInTheDocument();
  });

  it('lets somebody start over with a different address', async () => {
    const user = userEvent.setup();
    render(<EmailChangePanel copy={COPY} />);

    await ask(user);
    await user.click(await screen.findByRole('button', { name: 'Ask for a different address' }));

    expect(screen.getByRole('button', { name: 'Send the confirmation' })).toBeInTheDocument();
  });
});

describe('a refusal', () => {
  it('puts a wrong password under the password field', async () => {
    const user = userEvent.setup();
    requestMock.mockRejectedValue(
      new ApiError(403, {
        type: 'https://ideanest.az/problems/incorrect-password',
        title: 'Password required',
        detail: 'That is not the password on this account.',
      }),
    );

    render(<EmailChangePanel copy={COPY} />);
    await ask(user);

    const field = await screen.findByLabelText(/Current password/u);
    expect(field).toHaveAccessibleDescription(/not the password on this account/u);
    expect(field).toHaveAttribute('aria-invalid', 'true');
  });

  it('puts a taken address under the address field, and says so plainly', async () => {
    const user = userEvent.setup();
    requestMock.mockRejectedValue(
      new ApiError(409, {
        type: 'https://ideanest.az/problems/email-already-in-use',
        title: 'Address unavailable',
        detail: 'That address already has an account.',
      }),
    );

    render(<EmailChangePanel copy={COPY} />);
    await ask(user, 'taken@example.com');

    // Saying so is not registration's enumeration oracle: the caller is signed in, the endpoint
    // is limited per account, and silence would leave somebody waiting for a confirmation that
    // is never coming.
    expect(await screen.findByLabelText(/New email address/u)).toHaveAccessibleDescription(
      /already has an account/u,
    );
    expect(screen.getByRole('button', { name: 'Send the confirmation' })).toBeInTheDocument();
  });
});
