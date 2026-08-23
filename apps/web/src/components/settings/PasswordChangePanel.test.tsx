import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { SIGN_IN_AFTER_PASSWORD_CHANGE, changePassword } from '../../lib/auth/credentials';
import { PasswordChangePanel } from './PasswordChangePanel';

/**
 * §4.1's A-13 — issue #277.
 *
 * WHAT THESE COVER:
 *
 *   - **the sign-out is announced before the button is pressed, not after.**
 *     `CredentialController` puts that job on the client in as many words, and a sign-out
 *     nobody was warned about is an application that appears to have crashed at the exact
 *     moment somebody touched their password.
 *   - **succeeding ends the session and lands on the sign-in page.** The service revokes every
 *     session including this one, so there is no success state this panel could render — the
 *     route guard would move the reader off a private path before it could be read.
 *   - **a wrong current password is a 403 and keeps the session.** It goes under the field it
 *     is about, wired to the control, rather than being a heading nobody can act on.
 *   - the policy's own words are shown for a refused new password. This form states no minimum
 *     of its own, which would be a second opinion that goes stale.
 *   - the two new-password boxes are compared here and a mismatch never reaches the service.
 */

const replaced: string[] = [];
const signOutMock = vi.fn();

vi.mock('next/navigation', () => ({
  useRouter: () => ({
    push: () => {},
    replace: (href: string) => replaced.push(href),
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => {},
  }),
}));

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
    signOut: signOutMock,
  }),
}));

vi.mock('../../lib/auth/credentials', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/auth/credentials')>()),
  changePassword: vi.fn(),
}));

const changeMock = vi.mocked(changePassword);

beforeEach(() => {
  replaced.length = 0;
  signOutMock.mockReset();
  signOutMock.mockResolvedValue(undefined);
  changeMock.mockReset();
  changeMock.mockResolvedValue(undefined);
});

afterEach(cleanup);

async function change(
  user: ReturnType<typeof userEvent.setup>,
  options: { current?: string; next?: string; repeated?: string } = {},
): Promise<void> {
  const next = options.next ?? 'a much longer password';
  await user.type(screen.getByLabelText(/Current password/u), options.current ?? 'the old one');
  await user.type(screen.getByLabelText(/^New password\*/u), next);
  await user.type(screen.getByLabelText(/New password again/u), options.repeated ?? next);
  await user.click(screen.getByRole('button', { name: /Change password and sign out/u }));
}

describe('before anything is submitted', () => {
  it('says that succeeding signs every browser out, including this one', () => {
    render(<PasswordChangePanel />);

    const warning = screen.getByText(/ends every session on the account, including this one/u);
    expect(warning).toBeInTheDocument();
    // And the control repeats it, so somebody who skipped the paragraph still reads it on the
    // thing they are about to press.
    expect(
      screen.getByRole('button', { name: /Change password and sign out/u }),
    ).toBeInTheDocument();
  });

  it('asks for the current password, because a stolen token must not be enough', () => {
    render(<PasswordChangePanel />);

    expect(screen.getByLabelText(/Current password/u)).toBeInTheDocument();
  });
});

describe('a successful change', () => {
  it('sends both passwords, ends the session, and lands on the sign-in page', async () => {
    const user = userEvent.setup();
    render(<PasswordChangePanel />);

    await change(user, { current: 'the old one', next: 'a much longer password' });

    expect(changeMock).toHaveBeenCalledWith({
      currentPassword: 'the old one',
      newPassword: 'a much longer password',
    });
    // The session goes before the navigation, or a signed-in-looking shell renders over a
    // sign-in page.
    expect(signOutMock).toHaveBeenCalled();
    expect(replaced).toEqual([SIGN_IN_AFTER_PASSWORD_CHANGE]);
  });
});

describe('a refusal', () => {
  it('keeps the session for a wrong current password, and says so under that field', async () => {
    const user = userEvent.setup();
    changeMock.mockRejectedValue(
      new ApiError(403, {
        type: 'https://ideanest.az/problems/incorrect-password',
        title: 'Password required',
        detail: 'That is not the password on this account.',
      }),
    );

    render(<PasswordChangePanel />);
    await change(user, { current: 'not it' });

    const field = await screen.findByLabelText(/Current password/u);
    expect(field).toHaveAccessibleDescription(/not the password on this account/u);
    expect(field).toHaveAttribute('aria-invalid', 'true');

    // A 403 means the access token was accepted. Signing the reader out over a password typed
    // into the wrong box is the reaction the status was chosen to prevent.
    expect(signOutMock).not.toHaveBeenCalled();
    expect(replaced).toEqual([]);
  });

  it('shows the policy’s own words under the new password, and states no rule of its own', async () => {
    const user = userEvent.setup();
    changeMock.mockRejectedValue(
      new ApiError(400, {
        type: 'https://ideanest.az/problems/weak-password',
        title: 'Password rejected',
        detail: 'A password must be at least 12 characters.',
      }),
    );

    render(<PasswordChangePanel />);
    await change(user, { next: 'short' });

    expect(await screen.findByLabelText(/^New password\*/u)).toHaveAccessibleDescription(
      /at least 12 characters/u,
    );
  });

  it('announces the refusal and moves focus onto it', async () => {
    const user = userEvent.setup();
    changeMock.mockRejectedValue(
      new ApiError(403, {
        type: 'https://ideanest.az/problems/incorrect-password',
        title: 'Password required',
        detail: 'That is not the password on this account.',
      }),
    );

    render(<PasswordChangePanel />);
    await change(user);

    const alerts = await screen.findAllByRole('alert');
    // The warning is one; the refusal is the one that took focus.
    const summary = alerts.find((alert) => alert.textContent?.includes('Password required'));
    expect(summary?.parentElement).toHaveFocus();
  });
});

describe('the two new-password boxes', () => {
  it('catch a typo without spending a request', async () => {
    const user = userEvent.setup();
    render(<PasswordChangePanel />);

    await change(user, { next: 'a much longer password', repeated: 'a much longer passwrod' });

    expect(changeMock).not.toHaveBeenCalled();
    expect(signOutMock).not.toHaveBeenCalled();
    expect(
      await screen.findByText('The two new passwords do not match'),
    ).toBeInTheDocument();
  });
});
