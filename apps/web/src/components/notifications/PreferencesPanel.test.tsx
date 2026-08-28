import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import {
  listPreferences,
  updatePreferences,
  type NotificationCategory,
  type NotificationChannel,
  type PreferenceSwitch,
} from '../../lib/notifications/api';
import { PreferencesPanel } from './PreferencesPanel';
import { preferencesCopyFrom } from '../../lib/i18n/notifications-copy';
import { translatorFor } from '../../test-copy';
/*
 * The copy the route would have resolved, built from `messages/en.json` by the same function it
 * calls — issue #324. Retyping the sentences here would give a test that passes whatever the
 * catalogue says, which is the opposite of what it is for.
 */
const COPY = preferencesCopyFrom(translatorFor('account.notifications'));

vi.mock('../../lib/notifications/api', () => ({
  listPreferences: vi.fn(),
  updatePreferences: vi.fn(),
}));

const listMock = vi.mocked(listPreferences);
const updateMock = vi.mocked(updatePreferences);

function preference(
  category: NotificationCategory,
  channel: NotificationChannel,
  overrides: Partial<PreferenceSwitch> = {},
): PreferenceSwitch {
  return {
    category,
    channel,
    mode: 'IMMEDIATE',
    stored: false,
    changeable: true,
    // §4.10 gives a digest to email only; in-app and push deliver as they happen.
    digestOffered: channel === 'EMAIL',
    ...overrides,
  };
}

/** Two categories: one ordinary, one §4.10 makes mandatory. */
const PAGE: readonly PreferenceSwitch[] = [
  preference('PLEDGES', 'IN_APP'),
  preference('PLEDGES', 'EMAIL'),
  preference('PLEDGES', 'PUSH', { mode: 'OFF', stored: true }),
  preference('SECURITY', 'IN_APP', { changeable: false }),
  preference('SECURITY', 'EMAIL', { changeable: false, digestOffered: false }),
  preference('SECURITY', 'PUSH', { changeable: false }),
];

beforeEach(() => {
  vi.clearAllMocks();
  listMock.mockResolvedValue(PAGE);
  updateMock.mockResolvedValue(PAGE);
});

/**
 * Appearance is reviewed in Storybook. These cover BEHAVIOUR and ACCESSIBILITY.
 *
 * The ones that carry the design are the two about `changeable` and `digestOffered`: both
 * are the service's answers, and a client that decided either for itself would drift from
 * §4.10 and offer somebody a choice the service then refuses with a 422.
 */
describe('PreferencesPanel', () => {
  it('announces that it is loading rather than showing a blank panel', () => {
    listMock.mockReturnValue(new Promise<readonly PreferenceSwitch[]>(() => {}));
    render(<PreferencesPanel copy={COPY} />);

    const label = screen.getByText('Loading your notification settings');
    expect(label.closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true');
  });

  it('gives every switch a labelled control', async () => {
    render(<PreferencesPanel copy={COPY} />);

    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(6));
    // Two categories, each with the three channels §4.10 defines.
    expect(screen.getAllByLabelText('In app')).toHaveLength(2);
    expect(screen.getAllByLabelText('Email')).toHaveLength(2);
    expect(screen.getAllByLabelText('Push')).toHaveLength(2);
  });

  it('offers a digest only on the channel the service says can batch', async () => {
    render(<PreferencesPanel copy={COPY} />);

    const email = (await screen.findAllByLabelText('Email'))[0] as HTMLSelectElement;
    const inApp = (await screen.findAllByLabelText('In app'))[0] as HTMLSelectElement;

    expect([...email.options].map((option) => option.value)).toEqual([
      'IMMEDIATE',
      'DIGEST',
      'OFF',
    ]);
    expect([...inApp.options].map((option) => option.value)).toEqual(['IMMEDIATE', 'OFF']);
  });

  /*
   * A security alert that cannot be silenced is something the account holder should be
   * able to see the reason for — which is why the service sends `changeable` rather than
   * leaving the switch out, and why the control is disabled with an explanation beside it
   * rather than simply absent.
   */
  it('draws a mandatory switch disabled, with the reason', async () => {
    render(<PreferencesPanel copy={COPY} />);

    const controls = await screen.findAllByLabelText('In app');
    expect(controls[1]).toBeDisabled();
    expect(
      screen.getAllByText('Always on. This is how you find out if somebody else reaches your account.'),
    ).toHaveLength(3);
  });

  it('shows a default as a default rather than as a choice somebody made', async () => {
    render(<PreferencesPanel copy={COPY} />);

    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(6));
    // Two of the three PLEDGES switches are unstored; the third was set to OFF. The
    // SECURITY row is mandatory, so it carries its own note rather than "Default".
    expect(screen.getAllByText('Default')).toHaveLength(2);
  });

  /*
   * The common case is an account that has never opened this page. Saying so is what
   * stops a screen full of resolved defaults reading as a set of choices somebody made and
   * has forgotten about.
   */
  it('says when nothing has ever been changed', async () => {
    listMock.mockResolvedValue(PAGE.map((entry) => ({ ...entry, stored: false })));
    render(<PreferencesPanel copy={COPY} />);

    expect(await screen.findByText('all at their defaults')).toBeInTheDocument();
  });

  it('stops saying so once something is stored', async () => {
    render(<PreferencesPanel copy={COPY} />);

    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(6));
    expect(screen.queryByText('all at their defaults')).not.toBeInTheDocument();
  });

  it('saves one switch as it is changed, and adopts the whole page back', async () => {
    const changed = PAGE.map((entry) =>
      entry.category === 'PLEDGES' && entry.channel === 'EMAIL'
        ? { ...entry, mode: 'DIGEST' as const, stored: true }
        : entry,
    );
    updateMock.mockResolvedValue(changed);
    render(<PreferencesPanel copy={COPY} />);

    const email = (await screen.findAllByLabelText('Email'))[0] as HTMLSelectElement;
    await userEvent.selectOptions(email, 'DIGEST');

    await waitFor(() =>
      expect(updateMock).toHaveBeenCalledWith([
        { category: 'PLEDGES', channel: 'EMAIL', mode: 'DIGEST' },
      ]),
    );
    expect(email).toHaveValue('DIGEST');
  });

  it('reports what it saved in a live region', async () => {
    render(<PreferencesPanel copy={COPY} />);

    const push = (await screen.findAllByLabelText('Push'))[0] as HTMLSelectElement;
    await userEvent.selectOptions(push, 'OFF');

    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('Saved. Your pledges by push: off.');
  });

  /*
   * The endpoint is all-or-nothing, so a refusal means nothing was written. The control
   * therefore has to go back to what the service last confirmed — a switch left showing
   * the new value would be the screen telling somebody their change was saved.
   */
  it('returns a refused switch to what the service last confirmed', async () => {
    updateMock.mockRejectedValue(
      new ApiError(422, { title: 'Mandatory', detail: 'That category cannot be silenced.' }, 'no'),
    );
    render(<PreferencesPanel copy={COPY} />);

    const email = (await screen.findAllByLabelText('Email'))[0] as HTMLSelectElement;
    await userEvent.selectOptions(email, 'OFF');

    expect(await screen.findByText('That category cannot be silenced.')).toBeInTheDocument();
    expect(email).toHaveValue('IMMEDIATE');
  });

  it('explains a rate limit in terms somebody can act on', async () => {
    updateMock.mockRejectedValue(new ApiError(429, null, 'Too many requests'));
    render(<PreferencesPanel copy={COPY} />);

    const email = (await screen.findAllByLabelText('Email'))[0] as HTMLSelectElement;
    await userEvent.selectOptions(email, 'OFF');

    expect(await screen.findByText(/Wait a moment and try again/)).toBeInTheDocument();
  });

  it('offers a retry when the read fails, and recovers', async () => {
    listMock
      .mockRejectedValueOnce(new ApiError(503, null, 'Service unavailable'))
      .mockResolvedValueOnce(PAGE);
    render(<PreferencesPanel copy={COPY} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(6));
  });

  it('says so plainly when the session has gone rather than reporting an error', async () => {
    listMock.mockRejectedValue(new ApiError(401, null, 'Not signed in'));
    render(<PreferencesPanel copy={COPY} />);

    expect(await screen.findByText('You are signed out')).toBeInTheDocument();
  });
});
