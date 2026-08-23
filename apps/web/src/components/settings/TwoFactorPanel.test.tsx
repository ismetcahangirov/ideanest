import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import {
  confirmTwoFactorEnrolment,
  disableTwoFactor,
  startTwoFactorEnrolment,
} from '../../lib/auth/twoFactor';
import { TwoFactorPanel } from './TwoFactorPanel';

/**
 * §4.1's A-07 — issue #278.
 *
 * WHAT THESE COVER:
 *
 *   - **enrolling is not enabling.** The screen says two-factor is not on until a code is
 *     entered, because `TwoFactorEnrolmentService` makes that the property that stops a phone
 *     dying mid-flow from becoming a lockout. A screen that congratulated somebody after
 *     `enable` would be lying about the state of their account.
 *   - **the recovery codes cannot be clicked past.** There is no re-issue endpoint, so the
 *     acknowledgement gates the only way off that step.
 *   - the "already enabled" refusal moves the panel to the off-path rather than being printed
 *     as an error. It is the answer to the question this screen cannot ask — `GET /v1/me`
 *     carries no `twoFactorEnabled`.
 *   - disabling costs the password and a proof, because otherwise the whole feature is worth
 *     exactly one password.
 */

vi.mock('../../lib/auth/twoFactor', () => ({
  startTwoFactorEnrolment: vi.fn(),
  confirmTwoFactorEnrolment: vi.fn(),
  disableTwoFactor: vi.fn(),
}));

const startMock = vi.mocked(startTwoFactorEnrolment);
const confirmMock = vi.mocked(confirmTwoFactorEnrolment);
const disableMock = vi.mocked(disableTwoFactor);

const ENROLMENT = {
  secret: 'JBSWY3DPEHPK3PXP',
  otpauthUri: 'otpauth://totp/IdeaNest:aysel?secret=JBSWY3DPEHPK3PXP',
  digits: 6,
  periodSeconds: 30,
  algorithm: 'SHA1',
};

beforeEach(() => {
  vi.clearAllMocks();
  startMock.mockResolvedValue(ENROLMENT);
  confirmMock.mockResolvedValue(['aaaa-1111', 'bbbb-2222']);
  disableMock.mockResolvedValue(undefined);
});

afterEach(cleanup);

async function reachTheSecret(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByRole('button', { name: 'Set it up' }));
  await user.type(screen.getByLabelText(/Current password/u), 'correct horse');
  await user.click(screen.getByRole('button', { name: 'Continue' }));
}

describe('enrolling', () => {
  it('does not claim two-factor is on before a code has been entered', async () => {
    const user = userEvent.setup();
    render(<TwoFactorPanel />);

    await reachTheSecret(user);

    expect(screen.getByText(/not on yet/u)).toBeInTheDocument();
    expect(startMock).toHaveBeenCalledWith('correct horse');
  });

  it('offers the secret both ways, because not every device can open a link', async () => {
    const user = userEvent.setup();
    render(<TwoFactorPanel />);

    await reachTheSecret(user);

    expect(screen.getByRole('link', { name: /Open in your authenticator/u })).toHaveAttribute(
      'href',
      ENROLMENT.otpauthUri,
    );
    expect(screen.getByText(ENROLMENT.secret)).toBeInTheDocument();
  });

  it('shows the recovery codes and refuses to move on until they are acknowledged', async () => {
    const user = userEvent.setup();
    render(<TwoFactorPanel />);

    await reachTheSecret(user);
    await user.type(screen.getByLabelText(/Code from your authenticator/u), '123456');
    await user.click(screen.getByRole('button', { name: 'Switch it on' }));

    expect(await screen.findByText('aaaa-1111')).toBeInTheDocument();
    expect(screen.getByText('This is the only time these are shown')).toBeInTheDocument();

    const done = screen.getByRole('button', { name: 'Done' });
    expect(done).toBeDisabled();

    await user.click(screen.getByLabelText(/I have saved these/u));
    expect(done).toBeEnabled();
  });

  it('moves to the off-path when the service says the account is already enrolled', async () => {
    startMock.mockRejectedValue(
      new ApiError(409, { detail: 'Two-factor authentication is already enabled.' }),
    );
    const user = userEvent.setup();
    render(<TwoFactorPanel />);

    await reachTheSecret(user);

    expect(
      await screen.findByRole('heading', { name: 'Turn two-factor authentication off' }),
    ).toBeInTheDocument();
    // The service's own sentence, not a paraphrase, and not an error treatment.
    expect(screen.getByText('Two-factor authentication is already enabled.')).toBeInTheDocument();
  });

  it('prints any other refusal as an error and keeps the password step', async () => {
    startMock.mockRejectedValue(new ApiError(401, { detail: 'That password or code is not valid.' }));
    const user = userEvent.setup();
    render(<TwoFactorPanel />);

    await reachTheSecret(user);

    expect(await screen.findByText('That password or code is not valid.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Current password/u)).toBeInTheDocument();
  });
});

describe('disabling', () => {
  it('sends the password and exactly one proof', async () => {
    const user = userEvent.setup();
    render(<TwoFactorPanel />);

    await user.click(screen.getByRole('button', { name: 'Turn it off' }));
    await user.type(screen.getByLabelText(/Current password/u), 'correct horse');
    await user.type(screen.getByLabelText(/Code from your authenticator/u), '123456');
    await user.click(screen.getByRole('button', { name: 'Turn it off' }));

    expect(disableMock).toHaveBeenCalledWith('correct horse', { kind: 'code', code: '123456' });
  });

  it('will not submit with a password alone', async () => {
    const user = userEvent.setup();
    render(<TwoFactorPanel />);

    await user.click(screen.getByRole('button', { name: 'Turn it off' }));
    await user.type(screen.getByLabelText(/Current password/u), 'correct horse');
    await user.click(screen.getByRole('button', { name: 'Turn it off' }));

    // Without a code the feature would be worth exactly one password.
    expect(disableMock).not.toHaveBeenCalled();
  });
});
