import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ProjectCard as ProjectCardData } from '../../lib/discovery/api';
import { ProjectCard } from './ProjectCard';

/**
 * D-05's card. Appearance is reviewed in Storybook; these cover what fails
 * silently.
 *
 * The three that carry the design: the money never becomes a JavaScript number,
 * the funded state is `--success` and never lime, and every badge is a word as
 * well as a colour.
 */

const CARD: ProjectCardData = {
  id: 'p-1',
  slug: 'a-field-recorder',
  creatorSlug: 'sound-lab',
  title: 'A field recorder',
  creator: { name: 'Sound Lab', slug: 'sound-lab' },
  image: { url: 'https://example.test/cover.jpg', width: 1600, height: 900 },
  goal: { amount: '5000.00', currency: 'AZN' },
  pledged: { amount: '2500.00', currency: 'AZN' },
  completionPercent: '50.00',
  backersCount: 12,
  daysLeft: 20,
  badge: 'live',
  state: 'LIVE',
  launchedAt: '2026-08-01T00:00:00Z',
  deadline: '2026-09-05T00:00:00Z',
};

function renderCard(overrides: Partial<ProjectCardData> = {}) {
  return render(<ProjectCard card={{ ...CARD, ...overrides }} />);
}

describe('ProjectCard', () => {
  it('carries every field D-05 asks for', () => {
    renderCard();

    expect(screen.getByRole('heading', { name: 'A field recorder' })).toBeInTheDocument();
    expect(screen.getByText('Sound Lab')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '50');
    expect(screen.getByText('20 days left')).toBeInTheDocument();
    expect(screen.getByText('12 backers')).toBeInTheDocument();
    // The badge is a word, not only a hue. Colour alone must never carry
    // meaning (docs/ui-kit.md §9.2).
    expect(screen.getByText('Live')).toBeInTheDocument();
  });

  it('links to the campaign at the address the API uses', () => {
    renderCard();

    // `GET /v1/projects/{creatorSlug}/{projectSlug}` — which is why the card
    // carries `creatorSlug` at all.
    expect(screen.getByRole('link', { name: 'A field recorder' })).toHaveAttribute(
      'href',
      '/projects/sound-lab/a-field-recorder',
    );
  });

  it('renders money from the string the API sent, at full precision', () => {
    // The amount never goes through a JavaScript number. `999999999999.99`
    // loses its last digit the moment it does, which is the entire reason money
    // crosses the API as a string (CLAUDE.md §3).
    renderCard({
      pledged: { amount: '999999999999.99', currency: 'AZN' },
      goal: { amount: '999999999999.99', currency: 'AZN' },
      completionPercent: '100.00',
    });

    expect(screen.getByText('999,999,999,999.99 AZN')).toBeInTheDocument();
    expect(screen.getByText('of 999,999,999,999.99 AZN goal')).toBeInTheDocument();
  });

  it('reads the completion from the string rather than rounding a float', () => {
    renderCard({ completionPercent: '126.49' });

    expect(screen.getByText('126% funded')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAccessibleName('126 percent of the goal');
  });

  it('shows the funded bar in success, never in lime', () => {
    const { container } = renderCard({ completionPercent: '100.00' });

    const fill = container.querySelector('[role="progressbar"] > div');
    // Lime says "hurry"; success says "achieved". A backer who sees lime and
    // reads "all is well" has been told the opposite of the truth (§2.4).
    expect(fill?.className).toContain('bg-success');
    expect(fill?.className).not.toContain('bg-lime-500');
  });

  it('keeps the in-progress bar in lime', () => {
    const { container } = renderCard({ completionPercent: '50.00' });

    const fill = container.querySelector('[role="progressbar"] > div');
    expect(fill?.className).toContain('bg-lime-500');
    expect(fill?.className).not.toContain('bg-success');
  });

  it('marks a campaign closing within 48 hours as urgent, in words as well as colour', () => {
    renderCard({ daysLeft: 1, state: 'LIVE' });

    const urgent = screen.getByText('1 day left');
    // A lime SURFACE with near-black text. Lime text on a light surface
    // measures 1.3:1 and is prohibited (§9.1).
    expect(urgent.className).toContain('bg-lime-500');
    expect(urgent.className).toContain('text-on-lime');
    // And the focus ring flips on a lime fill, or it is invisible (§9.3).
    expect(urgent).toHaveAttribute('data-on-lime');
  });

  it('does not shout about a campaign that already closed', () => {
    // `daysLeft` is zero once the deadline has passed, so a campaign that ended
    // a fortnight ago reports the same number as one closing tonight.
    renderCard({ daysLeft: 0, state: 'SUCCESSFUL', badge: 'successful' });

    expect(screen.queryByText('Last day')).not.toBeInTheDocument();
    expect(screen.getByText('Successful')).toBeInTheDocument();
  });

  it('says "Last day" while a live campaign is still taking pledges', () => {
    renderCard({ daysLeft: 0, state: 'LIVE' });

    expect(screen.getByText('Last day')).toBeInTheDocument();
  });

  it('renders a campaign with no goal, no deadline, and no cover', () => {
    // `foxtrot` in the service's own fixture: the row that finds a card which
    // cannot be built without a cover image, and a completion band that treats
    // "no goal" as zero percent. A percentage of nothing is undefined, not zero.
    renderCard({
      image: null,
      goal: null,
      completionPercent: null,
      daysLeft: null,
      deadline: null,
      badge: 'upcoming',
      state: 'PRELAUNCH',
      backersCount: 0,
    });

    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    expect(screen.getByText('Not open for pledges yet')).toBeInTheDocument();
    expect(screen.getByText('Upcoming')).toBeInTheDocument();
    expect(screen.getByText('0 backers')).toBeInTheDocument();
  });

  it('renders a cancelled campaign without a badge rather than with a wrong one', () => {
    // §4.3 has no word for it. Inventing a sixth, or folding it into
    // "unsuccessful", would tell a reader a withdrawn campaign failed to find
    // backers.
    renderCard({ badge: null, state: 'CANCELED' });

    for (const word of ['Live', 'Upcoming', 'Successful', 'Unsuccessful', 'Late pledge']) {
      expect(screen.queryByText(word)).not.toBeInTheDocument();
    }
  });

  it('hides the cover from assistive technology', () => {
    // The title beside it is the name of the campaign. An alt repeating it
    // makes every card announce itself twice.
    const { container } = renderCard();

    // An empty `alt` takes the image out of the accessibility tree entirely —
    // it has no role at all, which is what `queryByRole('img')` finding nothing
    // proves.
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(container.querySelector('img')).toHaveAttribute('alt', '');
  });
});
