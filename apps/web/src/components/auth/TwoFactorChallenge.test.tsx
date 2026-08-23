import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import { completeTwoFactor } from '../../lib/auth/twoFactor';
import { TwoFactorChallenge } from './TwoFactorChallenge';

/**
 * §4.1's A-07 and A-08 — issue #272.
 *
 * WHAT THESE COVER:
 *
 *   - **the challenge is never rendered.** It is a credential for the next few minutes, and
 *     the reason this is a step rather than a route is that a URL would write it to access
 *     logs, history and the `Referer` header. A test that reads the DOM for it is the check
 *     that keeps it out.
 *   - exactly one credential is sent, and the recovery code wins when both fields carry
 *     something — spending a recovery code that was not needed is worse than ignoring six
 *     digits somebody abandoned.
 *   - **a wrong code clears the field and keeps the form.** A refusal here is retryable, and
 *     leaving six wrong digits in place means the next attempt starts by deleting them.
 *   - an expired challenge stops offering a form that cannot succeed.
 */

vi.mock('../../lib/auth/twoFactor', () => ({ completeTwoFactor: vi.fn() }));

const completeMock = vi.mocked(completeTwoFactor);

function renderChallenge(overrides: { expiresInSeconds?: number } = {}) {
  const onSignedIn = vi.fn();
  const onStartOver = vi.fn();

  render(
    <TwoFactorChallenge
      challenge="a-secret-challenge-value"
      expiresInSeconds={overrides.expiresInSeconds ?? 300}
      onSignedIn={onSignedIn}
      onStartOver={onStartOver}
    />,
  );

  return { onSignedIn, onStartOver };
}

beforeEach(() => {
  completeMock.mockReset();
  completeMock.mockResolvedValue(undefined);
});

afterEach(cleanup);

describe('the challenge step', () => {
  it('never puts the challenge on the page', () => {
    renderChallenge();
    expect(document.body.textContent).not.toContain('a-secret-challenge-value');
  });

  it('says the password was accepted, so a refusal here is not read as a wrong password', () => {
    renderChallenge();
    expect(screen.getByText('Your password was accepted')).toBeInTheDocument();
  });

  it('sends the code and hands the session back to its caller', async () => {
    const user = userEvent.setup();
    const { onSignedIn } = renderChallenge();

    await user.type(screen.getByLabelText(/Authentication code/u), '123456');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(completeMock).toHaveBeenCalledWith('a-secret-challenge-value', {
      kind: 'code',
      code: '123456',
    });
    expect(onSignedIn).toHaveBeenCalled();
  });

  it('offers the recovery code without making somebody find another screen', async () => {
    const user = userEvent.setup();
    renderChallenge();

    await user.click(screen.getByText('I cannot reach my authenticator'));
    await user.type(screen.getByLabelText(/Recovery code/u), 'abcd-efgh');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(completeMock).toHaveBeenCalledWith('a-secret-challenge-value', {
      kind: 'recovery-code',
      recoveryCode: 'abcd-efgh',
    });
  });

  it('spends the recovery code rather than the digits when both were typed', async () => {
    const user = userEvent.setup();
    renderChallenge();

    await user.type(screen.getByLabelText(/Authentication code/u), '123456');
    await user.click(screen.getByText('I cannot reach my authenticator'));
    await user.type(screen.getByLabelText(/Recovery code/u), 'abcd-efgh');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(completeMock).toHaveBeenCalledWith(expect.anything(), {
      kind: 'recovery-code',
      recoveryCode: 'abcd-efgh',
    });
  });

  it('prints the service’s refusal and clears the field for another try', async () => {
    completeMock.mockRejectedValue(
      new ApiError(401, { title: 'That code is not valid', detail: 'Check the app and try again.' }),
    );
    const user = userEvent.setup();
    renderChallenge();

    const field = screen.getByLabelText(/Authentication code/u);
    await user.type(field, '000000');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('Check the app and try again.')).toBeInTheDocument();
    expect(field).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Continue' })).toBeInTheDocument();
  });

  it('does not send an empty submission', async () => {
    const user = userEvent.setup();
    renderChallenge();

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(completeMock).not.toHaveBeenCalled();
  });

  it('withdraws the form once the challenge has expired', async () => {
    vi.useFakeTimers();
    try {
      const { onStartOver } = renderChallenge({ expiresInSeconds: 1 });
      // `act`, because the expiry is a `setTimeout` that sets state: without it the timer
      // fires and React never flushes the render it caused.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1_100);
      });

      expect(screen.getByText('This challenge has expired')).toBeInTheDocument();
      expect(screen.queryByLabelText(/Authentication code/u)).not.toBeInTheDocument();

      act(() => screen.getByRole('button', { name: 'Sign in again' }).click());
      expect(onStartOver).toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('sets no timer when the service did not say how long the challenge has', async () => {
    vi.useFakeTimers();
    try {
      renderChallenge({ expiresInSeconds: 0 });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(60 * 60 * 1000);
      });

      // Guessing an expiry would tell somebody their challenge had lapsed when it had not.
      expect(screen.queryByText('This challenge has expired')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
