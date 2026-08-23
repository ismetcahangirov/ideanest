import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { register } from '../../lib/auth/api';
import { SessionProvider } from '../session/SessionProvider';
import { RegisterForm } from './RegisterForm';

/**
 * §4.1's A-01 — issue #269.
 *
 * WHAT THESE COVER:
 *
 *   - **the enumeration rule holds on this side of the wire.** `POST /v1/auth/register`
 *     answers 202 whether or not the address was already registered, and a client that said
 *     "that address is taken" would undo the whole reason for that decision. The success
 *     screen is asserted to be true either way.
 *   - a registration does not sign anybody in and does not navigate. There is no session yet
 *     and the screen says what actually happens next.
 *   - the password rule is the service's, printed verbatim from its refusal. A minimum typed
 *     into this form would be a second opinion that goes stale.
 *   - there is no terms-and-conditions consent line, because there is no document behind one.
 */

let search = '';

vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  // `SessionProvider` reads it for the private-route guard; nothing on this screen is private.
  usePathname: () => '/register',
  useRouter: () => ({
    push: () => {},
    replace: () => {},
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => {},
  }),
}));

vi.mock('../../lib/auth/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/auth/api')>()),
  register: vi.fn(),
}));

/*
 * #273 put the provider buttons on this screen, and #272 the two-factor branch behind them, so
 * the form now reads the session through `useSignInOutcome` — a provider sign-in creates one.
 * The bootstrap is stubbed rather than the provider being replaced: what is under test is the
 * registration path, and a `SessionProvider` that resolves to "signed out" is the state
 * somebody filling in this form is actually in.
 */
vi.mock('../../lib/session/session', () => ({ fetchSession: vi.fn().mockResolvedValue(null) }));
vi.mock('../../lib/api/access-token', () => ({ signOut: vi.fn().mockResolvedValue(undefined) }));

const registerMock = vi.mocked(register);

function renderForm() {
  return render(
    <SessionProvider>
      <RegisterForm />
    </SessionProvider>,
  );
}

async function fillAndSubmit(
  user: ReturnType<typeof userEvent.setup>,
  email = 'aysel@example.com',
) {
  await user.type(screen.getByLabelText(/^Name/u), 'Aysel');
  await user.type(screen.getByLabelText(/Email address/u), email);
  await user.type(screen.getByLabelText(/^Password/u), 'a-long-enough-password');
  await user.click(screen.getByRole('button', { name: 'Create account' }));
}

beforeEach(() => {
  search = '';
  registerMock.mockReset();
});

afterEach(cleanup);

describe('a successful registration', () => {
  it('shows the check-your-email state and echoes the address back', async () => {
    const user = userEvent.setup();
    registerMock.mockResolvedValue(undefined);
    renderForm();

    await fillAndSubmit(user);

    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument();
    // The address is the reader's own and a typo in it is the commonest reason nothing arrives.
    expect(screen.getByText('aysel@example.com')).toBeInTheDocument();
  });

  it('says nothing about whether the account already existed', async () => {
    const user = userEvent.setup();
    registerMock.mockResolvedValue(undefined);
    renderForm();

    await fillAndSubmit(user);
    await screen.findByRole('heading', { name: 'Check your email' });

    // Anything of this shape would be the enumeration oracle the 202 exists to prevent.
    expect(screen.queryByText(/already registered/iu)).toBeNull();
    expect(screen.queryByText(/already taken/iu)).toBeNull();
    expect(screen.queryByText(/account was created/iu)).toBeNull();
  });

  it('replaces the form rather than leaving it underneath', async () => {
    const user = userEvent.setup();
    registerMock.mockResolvedValue(undefined);
    renderForm();

    await fillAndSubmit(user);
    await screen.findByRole('heading', { name: 'Check your email' });

    expect(screen.queryByRole('button', { name: 'Create account' })).toBeNull();
    expect(screen.queryByLabelText(/^Password/u)).toBeNull();
  });

  it('trims what was typed before sending it', async () => {
    const user = userEvent.setup();
    registerMock.mockResolvedValue(undefined);
    renderForm();

    await fillAndSubmit(user, '  aysel@example.com  ');
    await screen.findByRole('heading', { name: 'Check your email' });

    expect(registerMock.mock.calls[0]?.[0].email).toBe('aysel@example.com');
  });
});

describe('a refused registration', () => {
  it('prints the password policy’s own words beside the password field', async () => {
    const user = userEvent.setup();
    registerMock.mockRejectedValue(
      new ApiError(400, {
        title: 'Password rejected',
        detail: 'A password must be at least 12 characters.',
        errors: { password: 'A password must be at least 12 characters' },
      }),
    );
    renderForm();

    await fillAndSubmit(user);

    expect(await screen.findByText('Password rejected')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Password/u)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('A password must be at least 12 characters')).toBeInTheDocument();
  });

  it('keeps the form so the reader can correct it', async () => {
    const user = userEvent.setup();
    registerMock.mockRejectedValue(new ApiError(400, { title: 'Invalid request' }));
    renderForm();

    await fillAndSubmit(user);

    expect(await screen.findByText('Invalid request')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create account' })).toBeInTheDocument();
  });

  it('states its own password minimum nowhere', () => {
    renderForm();
    // The policy decides, and `RegistrationRequest` deliberately carries no length annotation
    // so that one place decides what is acceptable.
    expect(screen.queryByText(/\d+ characters/u)).toBeNull();
  });
});

describe('the links', () => {
  it('carries the return path on to the sign-in page', () => {
    search = 'next=%2Fsettings';
    renderForm();

    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/sign-in?next=%2Fsettings',
    );
  });

  it('offers no consent to a document that has not been written', () => {
    renderForm();
    expect(screen.queryByText(/terms/iu)).toBeNull();
    expect(screen.queryByRole('link', { name: /privacy/iu })).toBeNull();
  });
});
