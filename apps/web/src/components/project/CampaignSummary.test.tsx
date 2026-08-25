import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { CampaignPage } from '../../lib/projects/publicPage';
import { readCampaignPage } from '../../lib/projects/publicPage';
import type { ProjectPageResponse } from '../../lib/api/server';
import { fetchSession } from '../../lib/session/session';
import { SessionProvider } from '../session/SessionProvider';
import { CampaignSummary } from './CampaignSummary';
import { CampaignOutcomeNotice } from './CampaignOutcomeNotice';
import { CampaignRewards } from './CampaignRewards';

/**
 * What a campaign page says about a campaign, and what it must never say.
 *
 * These are rendered rather than screenshotted because every one of them is a statement
 * about money or urgency that a picture cannot check:
 *
 *   - the funding figures are in the markup, which is the whole of #119;
 *   - "funded" is `--success` and lime is only ever a countdown (ui-kit §2.4);
 *   - a closed campaign shows what it raised at the deadline beside what has been
 *     collected since — #63's rule, and the one number a backer would misread;
 *   - a sold-out tier is shown and says so in words, never merely greyed (PL-01, §9.2).
 *
 * THE HEADER NEEDS A SESSION PROVIDER SINCE #281, because `CampaignActions` reads one. The
 * session is stubbed rather than the component mocked away: the save and share controls are
 * part of what §4.4 asks this header for, and a test that rendered the header without them
 * would stop noticing the day they disappeared.
 */

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

const sessionMock = vi.mocked(fetchSession);

const PATH = '/projects/ayan/coffee-table-book';

/** The header, with the session provider it needs and the clock a countdown is measured on. */
function renderSummary(page: CampaignPage) {
  return render(
    <SessionProvider>
      <CampaignSummary campaign={page} path={PATH} now={NOW} />
    </SessionProvider>,
  );
}

const NOW = new Date('2026-08-19T12:00:00Z');

function campaign(overrides: Partial<ProjectPageResponse> = {}): CampaignPage {
  const page = readCampaignPage(
    {
      id: '0193f2a1-0000-7000-8000-000000000001',
      slug: 'coffee-table-book',
      state: 'LIVE',
      title: 'A coffee table book',
      blurb: 'Two hundred photographs of Baku.',
      creator: { slug: 'ayan', name: 'Ayan Q', avatarUrl: null },
      category: { slug: 'design', name: 'Design' },
      coverImage: null,
      goal: { amount: '10000.00', currency: 'AZN' },
      pledged: { amount: '12500.00', currency: 'AZN' },
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
});

afterEach(cleanup);

describe('the campaign summary', () => {
  it('puts the campaign in the markup rather than in a request', () => {
    renderSummary(campaign());

    expect(screen.getByRole('heading', { level: 1, name: 'A coffee table book' })).toBeInTheDocument();
    expect(screen.getByText('Two hundred photographs of Baku.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ayan Q' })).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  /**
   * Text as well as a bar (ui-kit §8.2). A bar that only changes colour at 100% has said
   * nothing to a reader with a colour-vision deficiency and nothing at all to a screen
   * reader, so the figure is printed and the bar carries an accessible name.
   */
  it('states the completion as words as well as a bar', () => {
    renderSummary(campaign());

    expect(screen.getByText('125%')).toBeInTheDocument();
    expect(screen.getByText('funded')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAccessibleName(
      'Funding: 125 percent of the goal',
    );
  });

  /**
   * Lime is urgency and only urgency. A campaign at 125% has funded, and funding is
   * `--success`; a lime badge here would tell a backer to hurry about something that is
   * finished.
   */
  it('does not put a lime countdown on a campaign with ten days left', () => {
    const { container } = renderSummary(campaign());

    // The days remaining are still stated in prose beside the goal — that is information.
    // What must not be there is the lime surface, which is the word "hurry".
    expect(screen.getByText(/10 days left/)).toBeInTheDocument();
    expect(container.querySelector('[data-on-lime]')).toBeNull();
  });

  it('puts a lime countdown on a campaign closing within 48 hours', () => {
    const { container } = renderSummary(campaign({ deadline: '2026-08-20T18:00:00Z' }));

    const urgent = container.querySelector('[data-on-lime]');
    expect(urgent).not.toBeNull();
    expect(urgent).toHaveTextContent('1 day left');
    // A lime SURFACE with near-black text. Lime text on a light surface measures 1.3:1 and
    // is prohibited outright (ui-kit §9.1), so the class pair is the assertion.
    expect(urgent).toHaveClass('bg-lime-500', 'text-on-lime');
  });

  /**
   * `daysLeft` is floored at zero, so a campaign that closed a fortnight ago reports the
   * same number as one closing tonight. "Last day" on the former is a lie.
   */
  it('does not count down a campaign that has already closed', () => {
    renderSummary(campaign({ state: 'SUCCESSFUL', deadline: '2026-08-01T00:00:00Z' }));

    expect(screen.queryByText('Last day')).not.toBeInTheDocument();
    expect(screen.getByText('Funded')).toBeInTheDocument();
  });

  it('renders a pre-launch campaign that has no goal, without inventing a percentage', () => {
    renderSummary(campaign({ state: 'PRELAUNCH', goal: undefined, deadline: undefined }));

    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    expect(screen.queryByText('0%')).not.toBeInTheDocument();
  });
});

describe('the outcome notice', () => {
  const closed = campaign({
    state: 'SUCCESSFUL',
    deadline: '2026-08-18T00:00:00Z',
    pledged: { amount: '9568.00', currency: 'AZN' },
    outcome: {
      goal: { amount: '10000.00', currency: 'AZN' },
      pledged: { amount: '12500.00', currency: 'AZN' },
      backersCount: 80,
      finalisedAt: '2026-08-18T00:00:00Z',
    },
  } as Partial<ProjectPageResponse>);

  /**
   * #63's sentence, on the surface that reports it. The live total has fallen below the
   * goal because collections failed; the campaign still funded, and both numbers are on the
   * page because conflating them is how somebody concludes their pledge vanished.
   */
  it('shows what the campaign raised at the deadline, not what has been collected since', () => {
    render(<CampaignOutcomeNotice campaign={closed} />);

    expect(screen.getByRole('heading', { name: /was funded/ })).toBeInTheDocument();
    expect(screen.getByText(/12,?500/)).toBeInTheDocument();
    expect(screen.getByText(/80 backers/)).toBeInTheDocument();
  });

  it('tells the backers of a failed campaign that nobody was charged', () => {
    render(
      <CampaignOutcomeNotice
        campaign={campaign({
          state: 'UNSUCCESSFUL',
          deadline: '2026-08-18T00:00:00Z',
          pledged: { amount: '400.00', currency: 'AZN' },
          outcome: {
            goal: { amount: '10000.00', currency: 'AZN' },
            pledged: { amount: '400.00', currency: 'AZN' },
            backersCount: 3,
            finalisedAt: '2026-08-18T00:00:00Z',
          },
        } as Partial<ProjectPageResponse>)}
      />,
    );

    expect(screen.getByRole('heading', { name: /did not reach its goal/ })).toBeInTheDocument();
    expect(screen.getByText(/Nobody was charged/)).toBeInTheDocument();
  });

  it('renders nothing while the campaign is still running', () => {
    const { container } = render(<CampaignOutcomeNotice campaign={campaign()} />);

    expect(container).toBeEmptyDOMElement();
  });
});

describe('the reward tiers', () => {
  /** PL-01: a sold-out tier stays on the page, and says so in words. */
  it('shows a sold-out tier rather than hiding it', () => {
    render(
      <CampaignRewards
        tiers={[
          {
            id: 'tier-1',
            title: 'Early bird',
            description: 'The book, signed.',
            price: { amount: '45.00', currency: 'AZN' },
            remainingQuantity: 0,
            imageUrl: null,
          },
          {
            id: 'tier-2',
            title: 'Standard',
            description: null,
            price: { amount: '60.00', currency: 'AZN' },
            remainingQuantity: null,
            imageUrl: null,
          },
        ]}
      />,
    );

    expect(screen.getByRole('heading', { name: 'Early bird' })).toBeInTheDocument();
    expect(screen.getByText('Sold out')).toBeInTheDocument();
    // An unlimited tier says nothing about how many are left, because there is no number.
    expect(screen.queryByText(/of this reward left/)).not.toBeInTheDocument();
  });

  it('renders nothing when a campaign offers no tiers', () => {
    const { container } = render(<CampaignRewards tiers={[]} />);

    expect(container).toBeEmptyDOMElement();
  });
});
