import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { signIn } from '../../lib/auth/api';
import { fetchSession } from '../../lib/session/session';
import { SessionProvider } from '../session/SessionProvider';
import { SignInForm } from './SignInForm';

/**
 * §4.1's A-03 — issue #268.
 *
 * WHAT THESE COVER, and each of them is a branch the issue names or a rule from §10.4:
 *
 *   - a suspension is a 403 and must NOT be rendered as a failed sign-in. The submit control
 *     is withdrawn, because the password was right and retrying it is a loop.
 *   - the rate limit says how long is left, and keeps the button — a reader who waits out the
 *     window needs it to still be there.
 *   - every other refusal is the service's own sentence, verbatim. Nothing here writes "wrong
 *     email or password": the endpoint deliberately does not say which half was wrong.
 *   - a two-factor challenge is a 200 and is not a failure. It is said plainly, because the
 *     challenge screen is #272 and is not built.
 *   - the return path is honoured, and an attacker-supplied one is not. An open redirect on a
 *     sign-in page is the classic phishing primitive.
 */

const replaced: string[] = [];
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
  usePathname: () => '/sign-in',
  useSearchParams: () => new URLSearchParams(search),
  useRouter: () => ({
    push: () => {},
    replace: (href: string) => replaced.push(href),
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => {},
  }),
}));

vi.mock('../../lib/auth/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/auth/api')>()),
  signIn: vi.fn(),
}));

vi.mock('../../lib/session/session', () => ({ fetchSession: vi.fn() }));
vi.mock('../../lib/api/access-token', () => ({ signOut: vi.fn().mockResolvedValue(undefined) }));

const signInMock = vi.mocked(signIn);
const sessionMock = vi.mocked(fetchSession);

function renderForm() {
  return render(
    <SessionProvider>
      <SignInForm />
    </SessionProvider>,
  );
}

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/Email address/u), 'aysel@example.com');
  await user.type(screen.getByLabelText(/^Password/u), 'a-long-enough-password');
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

beforeEach(() => {
  replaced.length = 0;
  search = '';
  signInMock.mockReset();
  sessionMock.mockReset();
  sessionMock.mockResolvedValue(null);
});

afterEach(cleanup);

describe('a successful sign-in', () => {
  it('reads the session before navigating, so the header is right on arrival', async () => {
    const user = userEvent.setup();
    signInMock.mockResolvedValue({ kind: 'signed-in' });
    renderForm();

    await fillAndSubmit(user);

    await waitFor(() => expect(replaced).toEqual(['/en']));
    // Twice: once for the bootstrap, once after signing in.
    expect(sessionMock.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('returns to where the guard interrupted', async () => {
    const user = userEvent.setup();
    search = 'next=%2Fsettings%2Fsessions';
    signInMock.mockResolvedValue({ kind: 'signed-in' });
    renderForm();

    await fillAndSubmit(user);

    await waitFor(() => expect(replaced).toEqual(['/en/settings/sessions']));
  });

  it('refuses a return path pointing at another origin', async () => {
    const user = userEvent.setup();
    search = 'next=https%3A%2F%2Fevil.test%2Flogin';
    signInMock.mockResolvedValue({ kind: 'signed-in' });
    renderForm();

    await fillAndSubmit(user);

    await waitFor(() => expect(replaced).toEqual(['/en']));
  });

  it('trims the address before sending it', async () => {
    const user = userEvent.setup();
    signInMock.mockResolvedValue({ kind: 'signed-in' });
    renderForm();

    await user.type(screen.getByLabelText(/Email address/u), '  aysel@example.com  ');
    await user.type(screen.getByLabelText(/^Password/u), 'a-long-enough-password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(signInMock).toHaveBeenCalled());
    expect(signInMock.mock.calls[0]?.[0].email).toBe('aysel@example.com');
  });
});

describe('a suspended account', () => {
  it('shows the service’s own sentence and withdraws the retry', async () => {
    const user = userEvent.setup();
    signInMock.mockRejectedValue(
      new ApiError(403, {
        code: 'ACCOUNT_SUSPENDED',
        title: 'Account suspended',
        detail: 'This account has been suspended. Contact support to appeal.',
      }),
    );
    renderForm();

    await fillAndSubmit(user);

    expect(await screen.findByText('Account suspended')).toBeInTheDocument();
    expect(screen.getByText(/Contact support to appeal/u)).toBeInTheDocument();
    // The password was right. Offering to try it again is a loop.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Sign in' })).toBeNull());
    expect(replaced).toEqual([]);
  });
});

describe('the rate limit', () => {
  it('says how long is left and keeps the button', async () => {
    const user = userEvent.setup();
    signInMock.mockRejectedValue(
      new ApiError(429, {
        title: 'Too many attempts',
        detail: 'Too many sign-in attempts.',
        retryAfterSeconds: 720,
      }),
    );
    renderForm();

    await fillAndSubmit(user);

    expect(await screen.findByText('Too many attempts')).toBeInTheDocument();
    expect(screen.getByText(/12 minutes/u)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });
});

describe('wrong credentials', () => {
  it('prints what the service said and invents nothing about which half was wrong', async () => {
    const user = userEvent.setup();
    signInMock.mockRejectedValue(
      new ApiError(401, {
        title: 'Not authenticated',
        detail: 'That email address and password do not match an account.',
      }),
    );
    renderForm();

    await fillAndSubmit(user);

    expect(
      await screen.findByText('That email address and password do not match an account.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/wrong password/iu)).toBeNull();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('puts a field message beside its field', async () => {
    const user = userEvent.setup();
    signInMock.mockRejectedValue(
      new ApiError(400, { title: 'Invalid request', errors: { email: 'That is not an email address' } }),
    );
    renderForm();

    await fillAndSubmit(user);

    const field = await screen.findByLabelText(/Email address/u);
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('That is not an email address')).toBeInTheDocument();
  });
});

describe('an account with a second factor', () => {
  it('says the password was accepted and does not report a failure', async () => {
    const user = userEvent.setup();
    signInMock.mockResolvedValue({
      kind: 'two-factor-required',
      challenge: 'ch_1',
      expiresInSeconds: 300,
    });
    renderForm();

    await fillAndSubmit(user);

    expect(await screen.findByText(/Your password was accepted/u)).toBeInTheDocument();
    expect(replaced).toEqual([]);
  });
});

describe('the links', () => {
  it('offers registration and carries the return path with it', () => {
    search = 'next=%2Fsettings';
    renderForm();

    expect(screen.getByRole('link', { name: 'Create one' })).toHaveAttribute('href', '/en/register?next=%2Fsettings');
  });

  /**
   * THIS ASSERTION WAS THE OPPOSITE UNTIL #271 SHIPPED, and the reason it flipped is worth
   * keeping: the link was refused while `POST /v1/auth/forgot-password` did not exist and
   * `/reset-password` answered 404, because "a link resolving to a 404 is worse on this screen
   * than anywhere else — it is offered to somebody who is already locked out". Both halves are
   * built now, so the screen that fails somebody has to carry the way out.
   */
  it('offers the password reset, now that there is one', () => {
    renderForm();

    expect(screen.getByRole('link', { name: 'Forgot your password?' })).toHaveAttribute('href', '/en/reset-password');
  });
});

/**
 * #277's password change cannot render its own confirmation: `POST /v1/auth/change-password`
 * revokes every session including the caller's, and the route guard moves a signed-out reader
 * off `/settings/password` before anything could be read. So the confirmation lands here.
 */
describe('the notice after a password change', () => {
  it('explains the sign-out somebody has just been given', () => {
    search = 'notice=password-changed';
    renderForm();

    expect(screen.getByText('Your password was changed')).toBeInTheDocument();
  });

  it('prints nothing for a value it does not know, because the URL is attacker-controlled', () => {
    // A notice whose text came out of a query string is a phishing page hosted on our own
    // domain. The parameter selects from a fixed set or it selects nothing.
    search = 'notice=your-account-needs-verifying-at-evil.test';
    renderForm();

    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText(/evil\.test/u)).toBeNull();
  });
});
