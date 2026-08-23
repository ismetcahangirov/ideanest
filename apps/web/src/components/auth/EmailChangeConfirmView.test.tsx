import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { ApiError } from '../../lib/api/problem';
import { confirmEmailChange } from '../../lib/auth/credentials';
import { EmailChangeConfirmView } from './EmailChangeConfirmView';

/**
 * §4.1's A-12, second half — issue #277.
 *
 * WHAT THESE COVER:
 *
 *   - **it spends the link once.** The token is claimed with a conditional update, so a second
 *     request is refused with "already used" — and React's development mode double-invokes
 *     effects, which without the guard would show that refusal to every developer who opened
 *     the page.
 *   - **a taken address is a different outcome from a dead link, and is told apart.** The
 *     service rolls the claim back on a 409 precisely so a change that becomes possible again
 *     can still be confirmed, so the screen must not say the link is used up.
 *   - the service's own sentence is what a refused link shows.
 *   - the outcome is announced, because a screen whose only change is a heading swapping is one
 *     a screen-reader user is not told about.
 */

let search = '';

vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
}));

vi.mock('../../lib/auth/credentials', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/auth/credentials')>()),
  confirmEmailChange: vi.fn(),
}));

const confirmMock = vi.mocked(confirmEmailChange);

beforeEach(() => {
  search = 'token=tok_1';
  confirmMock.mockReset();
  confirmMock.mockResolvedValue(undefined);
});

afterEach(cleanup);

describe('a link that works', () => {
  it('spends the token exactly once and says the address has moved', async () => {
    render(<EmailChangeConfirmView />);

    expect(await screen.findByText('Your email address has been changed')).toBeInTheDocument();
    expect(confirmMock).toHaveBeenCalledTimes(1);
    expect(confirmMock).toHaveBeenCalledWith('tok_1');
  });

  it('does not claim anybody was signed out, because an address change revokes nothing', async () => {
    render(<EmailChangeConfirmView />);

    expect(await screen.findByText(/still is/u)).toBeInTheDocument();
  });

  it('announces the outcome through a live region', async () => {
    render(<EmailChangeConfirmView />);

    await screen.findByText('Your email address has been changed');
    expect(screen.getByRole('status')).toHaveTextContent(
      'Your email address has been changed.',
    );
  });
});

describe('a link that cannot be used', () => {
  it('prints the service’s own sentence and says the account has not moved', async () => {
    confirmMock.mockRejectedValue(
      new ApiError(400, {
        type: 'https://ideanest.az/problems/invalid-verification-link',
        title: 'Verification failed',
        detail: 'This link has expired. Ask for a new one.',
      }),
    );

    render(<EmailChangeConfirmView />);

    expect(await screen.findByText('This link has expired. Ask for a new one.')).toBeInTheDocument();
    expect(screen.getByText(/Your account has not moved/u)).toBeInTheDocument();
  });
});

describe('an address somebody else took first', () => {
  it('is told apart from a dead link, and says the link is not spent', async () => {
    confirmMock.mockRejectedValue(
      new ApiError(409, {
        type: 'https://ideanest.az/problems/email-already-in-use',
        title: 'Address unavailable',
        detail: 'That address now has an account. Ask for the change again.',
      }),
    );

    render(<EmailChangeConfirmView />);

    expect(await screen.findByText('That address was taken first')).toBeInTheDocument();
    // The service rolls the claim back on this refusal, so the link survives.
    expect(screen.getByText(/has not been used up/u)).toBeInTheDocument();
    expect(screen.queryByText('This link cannot be used')).not.toBeInTheDocument();
  });
});

describe('no token at all', () => {
  it('explains rather than reporting an error, and asks the service nothing', async () => {
    search = '';
    render(<EmailChangeConfirmView />);

    expect(screen.getByText('Open the link we sent you')).toBeInTheDocument();
    expect(confirmMock).not.toHaveBeenCalled();
  });
});
