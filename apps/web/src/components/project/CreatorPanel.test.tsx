import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { ProjectPageResponse } from '../../lib/api/server';
import type { CampaignPage } from '../../lib/projects/publicPage';
import { readCampaignPage } from '../../lib/projects/publicPage';
import type { CreatorProject, PublicProfile } from '../../lib/projects/creatorProfile';
import { CreatorPanel } from './CreatorPanel';

/**
 * §4.4's Creator tab — #282.
 *
 * WHAT THESE COVER:
 *
 *   - **an absent field is an omitted row, never a placeholder.** §4.4 asks for "biography,
 *     history, previous projects, contact" and the platform publishes two of the four. On a
 *     page whose subject is whether to send somebody money, "Member since —" is a statement
 *     about the creator made out of the absence of a field.
 *   - **no count is printed.** There is no campaign count on the profile — counting one inside
 *     the `user` module would give it a dependency on `project` and `pledge` — and a count
 *     taken from the length of a capped list would understate a prolific creator.
 *   - **no contact control.** §4.9's C-12 is half built and there is no endpoint that would
 *     carry a message to this creator; a contact button that opened a mail client would be
 *     the platform pretending to have a feature.
 *   - **a private or missing profile degrades to the byline and explains nothing.** The
 *     endpoint answers 404 for an unknown slug, a deleted account and a private one alike, and
 *     an interface that said "this profile is private" would rebuild the oracle that 404
 *     exists to close.
 */

function campaign(overrides: Partial<ProjectPageResponse> = {}): CampaignPage {
  const page = readCampaignPage(
    {
      id: 'p-current',
      slug: 'coffee-table-book',
      state: 'LIVE',
      title: 'A coffee table book',
      creator: { slug: 'ayan', name: 'Ayan Q', avatarUrl: null },
      pledged: { amount: '2500.00', currency: 'AZN' },
      deadline: '2026-08-29T12:00:00Z',
      ...overrides,
    } as ProjectPageResponse,
    'ayan',
    new Date('2026-08-19T12:00:00Z'),
  );
  if (page === null) throw new Error('The fixture is not a renderable campaign');
  return page;
}

const PROFILE: PublicProfile = {
  slug: 'ayan',
  name: 'Ayan Q',
  avatarUrl: null,
  bio: 'Photographer in Baku.',
  joinedAt: '2024-02-01T00:00:00Z',
};

const OTHER: CreatorProject = {
  id: 'p-old',
  title: 'A folding bicycle',
  slug: 'a-folding-bicycle',
  creatorSlug: 'ayan',
  blurb: 'It folds.',
  state: 'SUCCESSFUL',
  goal: { amount: '10000.00', currency: 'AZN' },
  pledged: { amount: '12500.00', currency: 'AZN' },
  backersCount: 214,
  deadline: '2026-01-01T00:00:00Z',
  launchedAt: '2025-12-01T00:00:00Z',
  coverImage: null,
};

afterEach(cleanup);

describe('the creator tab', () => {
  it('links the creator through to the profile route the profile epic owns', () => {
    render(<CreatorPanel campaign={campaign()} profile={PROFILE} projects={[]} />);

    expect(screen.getByRole('link', { name: /Ayan Q/u })).toHaveAttribute('href', '/en/u/ayan');
  });

  it('shows the biography and the joining date the profile published', () => {
    render(<CreatorPanel campaign={campaign()} profile={PROFILE} projects={[]} />);

    expect(screen.getByText('Photographer in Baku.')).toBeInTheDocument();
    expect(screen.getByText(/Member since/u)).toBeInTheDocument();
  });

  it('omits the biography row rather than saying the creator has not written one', () => {
    render(
      <CreatorPanel
        campaign={campaign()}
        profile={{ ...PROFILE, bio: null }}
        projects={[]}
      />,
    );

    expect(screen.queryByText(/biography/iu)).not.toBeInTheDocument();
    expect(screen.queryByText('Photographer in Baku.')).not.toBeInTheDocument();
  });

  it('omits the joining date rather than printing an empty one', () => {
    render(
      <CreatorPanel
        campaign={campaign()}
        profile={{ ...PROFILE, joinedAt: null }}
        projects={[]}
      />,
    );

    expect(screen.queryByText(/Member since/u)).not.toBeInTheDocument();
  });

  it('lists the creator’s other campaigns, each with its state as a word', () => {
    render(<CreatorPanel campaign={campaign()} profile={PROFILE} projects={[OTHER]} />);

    expect(screen.getByRole('link', { name: /A folding bicycle/u })).toHaveAttribute('href', '/en/projects/ayan/a-folding-bicycle');
    // Never a hue on its own (§9.2): "Did not fund" is exactly the fact a reader must not
    // have to infer from a colour, so every card states its outcome in text.
    expect(screen.getByText('Funded')).toBeInTheDocument();
  });

  /**
   * The list is capped, so its length is not a count — and the profile publishes no count at
   * all, for a module-boundary reason that is not going to change soon.
   */
  it('prints no total number of campaigns anywhere', () => {
    const { container } = render(
      <CreatorPanel campaign={campaign()} profile={PROFILE} projects={[OTHER]} />,
    );

    expect(container.textContent).not.toMatch(/\d+\s+(campaigns|projects)/iu);
    expect(container.textContent).not.toMatch(/backed\s+\d+/iu);
  });

  it('offers no way to contact the creator, because there is no endpoint behind one', () => {
    render(<CreatorPanel campaign={campaign()} profile={PROFILE} projects={[OTHER]} />);

    expect(screen.queryByRole('link', { name: /contact/iu })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /contact|message/iu })).not.toBeInTheDocument();
  });

  it('says nothing at all when the creator has no other campaigns', () => {
    const { container } = render(
      <CreatorPanel campaign={campaign()} profile={PROFILE} projects={[]} />,
    );

    expect(container.textContent).not.toMatch(/no previous|no other/iu);
  });

  describe('when the profile cannot be read', () => {
    it('keeps the byline the campaign already carries', () => {
      render(<CreatorPanel campaign={campaign()} profile={null} projects={[]} />);

      expect(screen.getByText('Ayan Q')).toBeInTheDocument();
    });

    it('offers no profile link and explains nothing, because 404 covers three cases', () => {
      const { container } = render(
        <CreatorPanel campaign={campaign()} profile={null} projects={[]} />,
      );

      expect(screen.queryByRole('link', { name: /Ayan Q/u })).not.toBeInTheDocument();
      expect(container.textContent).not.toMatch(/private|deleted|unavailable/iu);
    });
  });
});
