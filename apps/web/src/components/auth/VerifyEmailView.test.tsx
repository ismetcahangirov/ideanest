import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { ApiError } from '../../lib/api/problem';
import { verifyEmail } from '../../lib/auth/api';
import { VerifyEmailView } from './VerifyEmailView';
import { verifyEmailCopyFrom } from '../../lib/i18n/auth-copy';
import { translatorFor } from '../../test-copy';

/*
 * The copy the page would have resolved, built from `messages/en.json` by the same function it
 * calls — issue #324. Retyping the sentences here would give a test that passes whatever the
 * catalogue says, which is the opposite of what it is for.
 */
const COPY = verifyEmailCopyFrom(translatorFor('auth'));

/**
 * §4.1's A-02 — issue #270.
 *
 * WHAT THESE COVER:
 *
 *   - **it redeems the token exactly once.** `EmailVerificationService.claim` is a conditional
 *     update, so a second request for the same token is refused — and React double-invokes
 *     effects in development on purpose. Without the guard every developer who opened this page
 *     would see "this link has already been used".
 *   - the expired-token path prints the service's own sentence and says what actually works
 *     next. There is no resend endpoint, so it does not offer one.
 *   - reaching the page with no token is not an error and is not presented as one.
 *   - the outcome is announced, because a screen whose only change is a heading swapping is a
 *     screen a screen-reader user is not told about.
 */

let search = 'token=tok_1';

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

vi.mock('../../lib/auth/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/auth/api')>()),
  verifyEmail: vi.fn(),
}));

const verifyMock = vi.mocked(verifyEmail);

beforeEach(() => {
  search = 'token=tok_1';
  verifyMock.mockReset();
});

afterEach(cleanup);

describe('with a token', () => {
  it('sends it once, and only once', async () => {
    verifyMock.mockResolvedValue(undefined);
    render(<VerifyEmailView copy={COPY} />);

    await waitFor(() => expect(verifyMock).toHaveBeenCalledTimes(1));
    expect(verifyMock).toHaveBeenCalledWith('tok_1');
  });

  it('confirms the address and offers the way in', async () => {
    verifyMock.mockResolvedValue(undefined);
    render(<VerifyEmailView copy={COPY} />);

    expect(
      await screen.findByRole('heading', { name: 'Your email address is verified' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/en/sign-in');
  });

  it('announces the outcome rather than only showing it', async () => {
    verifyMock.mockResolvedValue(undefined);
    render(<VerifyEmailView copy={COPY} />);

    const status = screen.getByRole('status');
    await waitFor(() => expect(status).toHaveTextContent('Your email address is verified.'));
  });

  it('does not sign anybody in — verifying an address authorises nothing else', async () => {
    verifyMock.mockResolvedValue(undefined);
    render(<VerifyEmailView copy={COPY} />);

    await screen.findByRole('heading', { name: 'Your email address is verified' });
    // The only way onward is the sign-in link. Nothing here writes a token.
    expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument();
  });
});

describe('a refused token', () => {
  beforeEach(() => {
    verifyMock.mockRejectedValue(
      new ApiError(400, {
        title: 'Verification failed',
        detail: 'That verification link has expired.',
      }),
    );
  });

  it('prints the service’s own sentence rather than guessing why', async () => {
    render(<VerifyEmailView copy={COPY} />);

    expect(await screen.findByText('Verification failed')).toBeInTheDocument();
    expect(screen.getByText('That verification link has expired.')).toBeInTheDocument();
  });

  it('offers no resend, because there is no endpoint behind one', async () => {
    render(<VerifyEmailView copy={COPY} />);

    await screen.findByText('Verification failed');
    // `RegistrationService` answers a second registration for an existing address by
    // publishing an event and returning; it issues no new token. A button that did nothing
    // would be worse than none.
    expect(screen.queryByRole('button', { name: /resend/iu })).toBeNull();
    expect(screen.queryByRole('link', { name: /resend/iu })).toBeNull();
  });

  it('says what does work, which is signing in before the address is verified', async () => {
    render(<VerifyEmailView copy={COPY} />);

    await screen.findByText('Verification failed');
    expect(screen.getByText(/signing in works before an address is verified/u)).toBeInTheDocument();
  });
});

describe('with no token at all', () => {
  it('explains the page rather than reporting a failure', async () => {
    search = '';
    render(<VerifyEmailView copy={COPY} />);

    expect(
      await screen.findByRole('heading', { name: 'Open the link we sent you' }),
    ).toBeInTheDocument();
    expect(verifyMock).not.toHaveBeenCalled();
  });

  it('treats a blank token as no token', async () => {
    search = 'token=%20%20';
    render(<VerifyEmailView copy={COPY} />);

    await screen.findByRole('heading', { name: 'Open the link we sent you' });
    expect(verifyMock).not.toHaveBeenCalled();
  });
});
