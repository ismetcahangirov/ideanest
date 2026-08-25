import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { resetPassword } from '../../lib/auth/passwordReset';
import { PasswordResetConfirmForm } from './PasswordResetConfirmForm';

/**
 * §4.1's A-06, second half — issue #271.
 *
 * WHAT THESE COVER, and each is a decision the issue or the service forces:
 *
 *   - **the one hour is said before the link dies and again after.** A link this short has to be
 *     described where somebody can still act on it, and a refusal an hour later has to say what
 *     the constraint was rather than being a generic error.
 *   - **the service's own sentence is what a refused link shows.** Expired, already used and
 *     never issued all arrive as one type with three different sentences, and only the service
 *     knows which. Collapsing them would tell somebody whose link expired that they had already
 *     used it — which reads as "somebody else opened this".
 *   - **a weak password keeps the form and the token.** `PasswordResetService` checks the
 *     policy before it claims the link precisely so a typo does not burn it, and a client that
 *     sent the reader back to ask for a new email would waste that.
 *   - the two boxes are compared in the browser and a mismatch never reaches the service.
 *   - a refusal is announced and takes focus.
 */

let search = '';

vi.mock('next/navigation', async (importOriginal) => ({
  /*
   * Spread first so the real module's other exports survive. `i18n/navigation.ts`
   * builds its wrappers at import time and reads `redirect` and `permanentRedirect`
   * while doing so, and a factory that replaced the module wholesale left those
   * undefined — which failed as a TypeError inside next-intl rather than anywhere
   * near the test that caused it.
   */
  ...(await importOriginal<typeof import('next/navigation')>()),
  useSearchParams: () => new URLSearchParams(search),
}));

vi.mock('../../lib/auth/passwordReset', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/auth/passwordReset')>()),
  resetPassword: vi.fn(),
}));

const resetMock = vi.mocked(resetPassword);

beforeEach(() => {
  search = 'token=tok_1';
  resetMock.mockReset();
  resetMock.mockResolvedValue(undefined);
});

afterEach(cleanup);

async function setPassword(
  user: ReturnType<typeof userEvent.setup>,
  first: string,
  second = first,
): Promise<void> {
  await user.type(screen.getByLabelText(/^New password\*/u), first);
  await user.type(screen.getByLabelText(/New password again/u), second);
  await user.click(screen.getByRole('button', { name: 'Set my password' }));
}

function invalidLink(detail: string): ApiError {
  return new ApiError(400, {
    type: 'https://ideanest.az/problems/invalid-verification-link',
    title: 'Verification failed',
    detail,
  });
}

describe('with a token in the URL', () => {
  it('says how long the link works before anything is submitted', async () => {
    render(<PasswordResetConfirmForm />);

    expect(screen.getByText(/works for one hour/u)).toBeInTheDocument();
    // And what succeeding costs, which is every other session.
    expect(screen.getByText(/signs out every browser/u)).toBeInTheDocument();
  });

  it('spends the token from the query string and confirms what it cost', async () => {
    const user = userEvent.setup();
    render(<PasswordResetConfirmForm />);

    await setPassword(user, 'a much longer password');

    expect(resetMock).toHaveBeenCalledWith('tok_1', 'a much longer password');
    expect(await screen.findByText('Your password is set')).toBeInTheDocument();
    expect(screen.getByText(/has been signed out/u)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/en/sign-in');
  });
});

describe('a link that cannot be used', () => {
  it('prints the service’s own sentence rather than guessing which refusal it is', async () => {
    const user = userEvent.setup();
    resetMock.mockRejectedValue(invalidLink('This link has expired. Ask for a new one.'));

    render(<PasswordResetConfirmForm />);
    await setPassword(user, 'a much longer password');

    expect(await screen.findByText('This link has expired. Ask for a new one.')).toBeInTheDocument();
    // Never invented, and never the other two sentences.
    expect(screen.queryByText(/already been used/u)).not.toBeInTheDocument();
  });

  it('distinguishes a spent link from an expired one, because the service does', async () => {
    const user = userEvent.setup();
    resetMock.mockRejectedValue(invalidLink('This link has already been used.'));

    render(<PasswordResetConfirmForm />);
    await setPassword(user, 'a much longer password');

    expect(await screen.findByText('This link has already been used.')).toBeInTheDocument();
  });

  it('withdraws the form and offers a new link, because pressing again cannot work', async () => {
    const user = userEvent.setup();
    resetMock.mockRejectedValue(invalidLink('This link has expired. Ask for a new one.'));

    render(<PasswordResetConfirmForm />);
    await setPassword(user, 'a much longer password');

    expect(await screen.findByText('This link cannot be used')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Set my password' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ask for a new link' })).toHaveAttribute('href', '/en/reset-password');
    // The constraint that produced this, restated where it explains the screen.
    expect(screen.getByText(/works for one hour and can be used once/u)).toBeInTheDocument();
  });
});

describe('a password the policy refuses', () => {
  it('keeps the form and the token, and shows the policy’s own words under the field', async () => {
    const user = userEvent.setup();
    resetMock.mockRejectedValue(
      new ApiError(400, {
        type: 'https://ideanest.az/problems/weak-password',
        title: 'Password rejected',
        detail: 'A password must be at least 12 characters.',
      }),
    );

    render(<PasswordResetConfirmForm />);
    await setPassword(user, 'short');

    // The link is NOT spent — the policy is checked before the token is claimed — so the same
    // token must remain submittable rather than sending somebody back for another email.
    expect(await screen.findByRole('button', { name: 'Set my password' })).toBeInTheDocument();
    expect(screen.queryByText('This link cannot be used')).not.toBeInTheDocument();

    const field = screen.getByLabelText(/^New password\*/u);
    expect(field).toHaveAccessibleDescription(/at least 12 characters/u);
    expect(field).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('the two boxes', () => {
  it('catch a typo in the browser and never send it', async () => {
    const user = userEvent.setup();
    render(<PasswordResetConfirmForm />);

    await setPassword(user, 'a much longer password', 'a much longer passwrod');

    expect(resetMock).not.toHaveBeenCalled();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The two passwords do not match');
    // The reader is put on the message rather than left on a button below it.
    expect(alert.parentElement).toHaveFocus();
    expect(screen.getByLabelText(/New password again/u)).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('without a token', () => {
  it('explains rather than reporting an error, and points at the way to get one', () => {
    search = '';
    render(<PasswordResetConfirmForm />);

    expect(screen.getByText('Open the link we sent you')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ask for a reset link' })).toHaveAttribute('href', '/en/reset-password');
  });
});
