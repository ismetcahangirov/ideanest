import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ProjectPageResponse } from '../../lib/api/server';
import type { CampaignPage } from '../../lib/projects/publicPage';
import { readCampaignPage } from '../../lib/projects/publicPage';
import { saveCampaign } from '../../lib/projects/api';
import { unsaveCampaign } from '../../lib/community/signals';
import { fetchSession, type Session } from '../../lib/session/session';
import { SessionProvider } from '../session/SessionProvider';
import { CampaignActions } from './CampaignActions';
import { CampaignMedia } from './CampaignMedia';
import { CampaignTabs } from './CampaignTabs';
import { CampaignTrustBlock, TRUST_COPY } from './CampaignTrustBlock';
import { CampaignCountdown } from './ViewerClock';

/**
 * §4.4's header, media player and trust block — #281 — and the tab list #282, #284 and #285
 * hang from.
 *
 * WHAT THESE COVER, and why each one is a test rather than a comment:
 *
 *   - **the trust copy is verbatim.** Each of its three sentences is a promise about
 *     somebody's money, and the third is the platform's entire commercial model stated to the
 *     person about to rely on it. A softened rewording would be a different promise, and
 *     nobody would notice until a backer quoted it back.
 *   - **there is no dead play button.** `ProjectPageResponse` carries no video field and
 *     §13.2's pipeline is not built, so a play affordance would be a promise the page cannot
 *     keep, made at the top of the page, to somebody deciding whether the creator keeps
 *     promises.
 *   - **the countdown does not shout.** `role="timer"` and an explicit `aria-live="off"`, so
 *     a screen reader announces the value on arrival and never again — a polite region here
 *     would interrupt whatever is being read, once a minute, for as long as the page is open.
 *   - **the tab list is a navigation landmark, not an ARIA tab widget.** Tab roles promise
 *     arrow-key behaviour these links do not have and cannot have without JavaScript, and a
 *     widget whose roles promise behaviour it lacks is worse than no roles.
 *   - **every icon-only affordance has a name that says which campaign it acts on**
 *     (docs/ui-kit.md §9.4).
 *   - **a signed-out reader gets a sign-in that returns here, never a control that fails.**
 */

vi.mock('../../lib/projects/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/projects/api')>()),
  saveCampaign: vi.fn(),
  remindMe: vi.fn(),
  forgetMe: vi.fn(),
}));
vi.mock('../../lib/community/signals', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/community/signals')>()),
  unsaveCampaign: vi.fn(),
}));
vi.mock('../../lib/session/session', () => ({ fetchSession: vi.fn() }));
vi.mock('../../lib/api/access-token', () => ({ signOut: vi.fn().mockResolvedValue(undefined) }));
vi.mock('next/navigation', async (importOriginal) => ({
  /*
   * Spread first so the real module's other exports survive. `i18n/navigation.ts`
   * builds its wrappers at import time and reads `redirect` and `permanentRedirect`
   * while doing so, and a factory that replaced the module wholesale left those
   * undefined — which failed as a TypeError inside next-intl rather than anywhere
   * near the test that caused it.
   */
  ...(await importOriginal<typeof import('next/navigation')>()),
  usePathname: () => '/projects/ayan/coffee-table-book',
  useRouter: () => ({
    push: () => {},
    replace: () => {},
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => {},
  }),
}));

const saveMock = vi.mocked(saveCampaign);
const unsaveMock = vi.mocked(unsaveCampaign);
const sessionMock = vi.mocked(fetchSession);

const PATH = '/projects/ayan/coffee-table-book';
const NOW = new Date('2026-08-19T12:00:00Z');

const ACCOUNT: Session = {
  id: 'u1',
  email: 'ayan@example.com',
  name: 'Ayan Q',
  slug: 'ayan',
  emailVerified: true,
};

function campaign(overrides: Partial<ProjectPageResponse> = {}): CampaignPage {
  const page = readCampaignPage(
    {
      id: '0193f2a1-0000-7000-8000-000000000001',
      slug: 'coffee-table-book',
      state: 'LIVE',
      title: 'A coffee table book',
      blurb: 'Two hundred photographs of Baku.',
      creator: { slug: 'ayan', name: 'Ayan Q', avatarUrl: null },
      goal: { amount: '10000.00', currency: 'AZN' },
      pledged: { amount: '2500.00', currency: 'AZN' },
      backersCount: 42,
      deadline: '2026-08-29T12:00:00Z',
      ...overrides,
    } as ProjectPageResponse,
    'ayan',
    NOW,
  );
  if (page === null) throw new Error('The fixture is not a renderable campaign');
  return page;
}

beforeEach(() => {
  vi.clearAllMocks();
  sessionMock.mockResolvedValue(null);
  saveMock.mockResolvedValue({ saved: true });
  unsaveMock.mockResolvedValue(undefined);
});

afterEach(cleanup);

describe('the media player', () => {
  it('renders the poster as the media, with no alternative text to duplicate the title', () => {
    const { container } = render(
      <CampaignMedia
        campaign={campaign({
          coverImage: { url: 'https://cdn.test/cover.jpg', width: 1600, height: 900 },
        } as Partial<ProjectPageResponse>)}
      />,
    );

    const image = container.querySelector('img');
    expect(image).not.toBeNull();
    expect(image).toHaveAttribute('alt', '');
  });

  /**
   * The whole point of #281's honest branch. A play control over a campaign that has no video
   * is a promise the page cannot keep; the affordance arrives with §13.2's pipeline.
   */
  it('offers no play control, because nothing on the platform has a video to play', () => {
    render(<CampaignMedia campaign={campaign()} />);

    expect(screen.queryByRole('button', { name: /play/iu })).not.toBeInTheDocument();
    expect(screen.queryByText(/play/iu)).not.toBeInTheDocument();
  });

  it('reserves the box for a campaign with no cover at all', () => {
    const { container } = render(<CampaignMedia campaign={campaign()} />);

    expect(container.firstElementChild).not.toBeNull();
    expect(container.querySelector('img')).toBeNull();
  });
});

describe('the live countdown', () => {
  it('is a timer that does not announce itself on every tick', () => {
    render(<CampaignCountdown deadline="2026-08-29T12:00:00Z" initialLabel="9 days, 12 hours" />);

    const timer = screen.getByRole('timer');
    expect(timer).toHaveAttribute('aria-live', 'off');
    expect(timer).toHaveAccessibleName('Time left to back this campaign: 9 days, 12 hours');
  });

  it('renders the server’s value into the markup rather than waiting for a tick', () => {
    render(<CampaignCountdown deadline="2026-08-29T12:00:00Z" initialLabel="9 days, 12 hours" />);

    expect(screen.getByRole('timer')).toHaveTextContent('9 days, 12 hours left');
  });

  it('renders nothing at all for a campaign that has closed', () => {
    const { container } = render(
      <CampaignCountdown deadline="2026-08-01T00:00:00Z" initialLabel={null} />,
    );

    expect(container).toBeEmptyDOMElement();
  });
});

describe('the trust block', () => {
  /** §4.4 prints these three sentences and calls them fixed copy on every project. */
  it('prints §4.4’s copy word for word', () => {
    render(<CampaignTrustBlock campaign={campaign()} />);

    expect(screen.getByText(TRUST_COPY)).toBeInTheDocument();
    expect(TRUST_COPY).toBe(
      'The platform connects creators with backers. Rewards are not guaranteed, but creators ' +
        'must keep backers informed. You are only charged if the project reaches its goal by ' +
        'the deadline.',
    );
  });

  it('states all or nothing with the goal and the deadline as a machine-readable instant', () => {
    const { container } = render(<CampaignTrustBlock campaign={campaign()} />);

    expect(screen.getByText(/All or nothing/u)).toBeInTheDocument();
    expect(screen.getByText('10,000.00 AZN')).toBeInTheDocument();
    expect(screen.getByText(/nobody is charged anything/u)).toBeInTheDocument();

    // The words are a presentation of the instant; the `datetime` is the fact.
    const time = container.querySelector('time');
    expect(time).toHaveAttribute('datetime', '2026-08-29T12:00:00Z');
  });

  it('uses the past tense for a campaign that has closed', () => {
    render(
      <CampaignTrustBlock
        campaign={campaign({ state: 'SUCCESSFUL', deadline: '2026-08-01T00:00:00Z' })}
      />,
    );

    expect(screen.getByText(/closed on/u)).toBeInTheDocument();
    expect(screen.queryByText(/nobody is charged anything/u)).not.toBeInTheDocument();
  });

  /**
   * A pre-launch campaign has neither a goal nor a closing date (§5.3). The rule is still
   * stated, because §4.4 requires it on every project; the date is not invented to have
   * something to name.
   */
  it('keeps the fixed copy and names no date when there is no deadline', () => {
    render(
      <CampaignTrustBlock
        campaign={campaign({ state: 'PRELAUNCH', goal: undefined, deadline: undefined })}
      />,
    );

    expect(screen.getByText(TRUST_COPY)).toBeInTheDocument();
    expect(screen.queryByText(/All or nothing/u)).not.toBeInTheDocument();
  });
});

describe('the tab list', () => {
  it('is a named navigation landmark rather than an ARIA tab widget', () => {
    render(<CampaignTabs active="campaign" path={PATH} />);

    expect(screen.getByRole('navigation', { name: 'Campaign sections' })).toBeInTheDocument();
    // Tab roles would promise arrow-key behaviour these links neither have nor need.
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(screen.queryAllByRole('tab')).toHaveLength(0);
  });

  it('makes every tab a real address, with the default tab on the bare path', () => {
    render(<CampaignTabs active="campaign" path={PATH} />);

    expect(screen.getByRole('link', { name: 'Campaign' })).toHaveAttribute('href', `/en${PATH}`);
    expect(screen.getByRole('link', { name: 'Comments' })).toHaveAttribute(
      'href',
      `/en${PATH}?tab=comments`,
    );
  });

  it('marks the current tab with aria-current rather than with colour alone', () => {
    render(<CampaignTabs active="comments" path={PATH} />);

    expect(screen.getByRole('link', { name: 'Comments' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Campaign' })).not.toHaveAttribute('aria-current');
  });
});

describe('the save, share and reminder controls', () => {
  function renderActions(page: CampaignPage = campaign()) {
    return render(
      <SessionProvider>
        <CampaignActions
          projectId={page.id}
          state={page.state}
          title={page.title}
          path={PATH}
        />
      </SessionProvider>,
    );
  }

  it('offers a signed-out reader a sign-in that returns to the campaign', async () => {
    renderActions();

    const link = await screen.findByRole('link', { name: 'Save' });
    expect(link).toHaveAttribute('href', `/en/sign-in?next=${encodeURIComponent(PATH)}`);
  });

  it('names the save control after the campaign it saves', async () => {
    sessionMock.mockResolvedValue(ACCOUNT);
    renderActions();

    expect(
      await screen.findByRole('button', { name: 'Save A coffee table book' }),
    ).toHaveAttribute('aria-pressed', 'false');
  });

  /**
   * The platform publishes no per-campaign "have I saved this", so the control offers the
   * action and reads the state from the answer. The write is idempotent, so a second press by
   * somebody who saved it last week is told the truth rather than quietly unsaving it.
   */
  it('reads the saved state from the service’s answer rather than assuming it', async () => {
    sessionMock.mockResolvedValue(ACCOUNT);
    renderActions();

    await userEvent.click(await screen.findByRole('button', { name: 'Save A coffee table book' }));

    await waitFor(() => {
      expect(saveMock).toHaveBeenCalledWith('0193f2a1-0000-7000-8000-000000000001');
    });
    const pressed = await screen.findByRole('button', {
      name: 'A coffee table book is saved',
    });
    expect(pressed).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('A coffee table book saved.')).toBeInTheDocument();
  });

  it('says what happened, because the effect of sharing is somewhere the reader cannot see', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    sessionMock.mockResolvedValue(ACCOUNT);
    renderActions();

    await userEvent.click(await screen.findByRole('button', { name: 'Share A coffee table book' }));

    await waitFor(() => expect(writeText).toHaveBeenCalled());
    expect(await screen.findByText('Link copied.')).toBeInTheDocument();
  });

  /**
   * `POST /v1/projects/{id}/remind` is a launch reminder: the service answers 409
   * "this campaign has already opened" for anything past PRELAUNCH. A control that was always
   * shown would fail on eight of the nine public states.
   */
  it('offers a reminder only where the endpoint would accept one', async () => {
    sessionMock.mockResolvedValue(ACCOUNT);
    renderActions();

    expect(await screen.findByRole('button', { name: /Save/u })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Remind me/u })).not.toBeInTheDocument();

    cleanup();
    renderActions(campaign({ state: 'PRELAUNCH', goal: undefined, deadline: undefined }));

    expect(
      await screen.findByRole('button', { name: 'Remind me when A coffee table book opens' }),
    ).toBeInTheDocument();
  });
});
