import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { ProfileSocialLink, PublicProfile } from '../../lib/profiles/api';
import { ProfileAbout } from './ProfileAbout';
import { profileCopyFrom } from '../../lib/i18n/profile-copy';
import { translatorFor } from '../../test-copy';
/*
 * The copy the route would have resolved, built from `messages/en.json` by the same function it
 * calls — issue #324. Retyping the sentences here would give a test that passes whatever the
 * catalogue says, which is the opposite of what it is for.
 */
const COPY = profileCopyFrom(translatorFor('profile'));

/**
 * §4.2's About tab — P-02, P-03 and P-06, issues #274 and #276.
 *
 * WHAT THESE COVER:
 *
 *   - **every user-supplied outbound link carries `rel="nofollow ugc noopener noreferrer"`.**
 *     `PublicProfileResponse`'s Javadoc gives a different reason for each token and states
 *     that the server cannot supply any of them: `nofollow ugc` because a link on an
 *     indexable profile is the cheapest backlink a spammer can get here, `noopener` because a
 *     new tab hands the destination a handle on this page, `noreferrer` because which profile
 *     somebody was reading is nobody's business but theirs. This is the assertion that keeps
 *     all four present rather than three.
 *   - **the address is visible, never hidden behind a word.** A link whose text conceals
 *     where it goes is the shape of every phishing link, and the author of this one is a
 *     stranger the reader has no reason to trust yet.
 *   - **the location is text and not a link**, because `/discover?city=` is one of the four
 *     options the discovery service declares and refuses (#47). A control that cannot work is
 *     a promise the interface breaks the first time somebody uses it.
 *   - **an absent field renders nothing, and an absent biography renders a sentence.** `null`
 *     is an answer this component has received rather than one it is waiting for, so a tab
 *     that opened onto a blank panel would read as one that failed to load.
 *   - the biography is text: a paragraph somebody typed keeps its breaks and nothing they
 *     typed becomes an element.
 */

const REL = 'nofollow ugc noopener noreferrer';

function link(overrides: Partial<ProfileSocialLink> = {}): ProfileSocialLink {
  return { platform: 'INSTAGRAM', url: 'https://instagram.com/aysel', ...overrides };
}

function profile(overrides: Partial<PublicProfile> = {}): PublicProfile {
  return {
    slug: 'aysel',
    name: 'Aysel',
    avatarUrl: null,
    bio: 'I make ceramics.',
    joinedAt: '2025-03-14T10:00:00Z',
    websiteUrl: 'https://aysel.example',
    location: { slug: 'baku', name: 'Bakı' },
    socialLinks: [link()],
    ...overrides,
  };
}

afterEach(cleanup);

describe('the outbound links', () => {
  it('gives the website all four rel tokens', () => {
    render(<ProfileAbout copy={COPY.about} locale="en" profile={profile()} />);

    const website = screen.getByRole('link', { name: 'https://aysel.example' });
    expect(website).toHaveAttribute('rel', REL);
    expect(website).toHaveAttribute('href', 'https://aysel.example');
    expect(website).toHaveAttribute('target', '_blank');
  });

  it('gives every social link the same four, and names the platform', () => {
    render(
      <ProfileAbout
        copy={COPY.about}
        locale="en"
        profile={profile({
          socialLinks: [link(), link({ platform: 'GITHUB', url: 'https://github.com/aysel' })],
        })}
      />,
    );

    for (const name of [/Instagram/u, /GitHub/u]) {
      expect(screen.getByRole('link', { name })).toHaveAttribute('rel', REL);
    }
  });

  it('shows where each link goes, rather than only what it is called', () => {
    render(<ProfileAbout copy={COPY.about} locale="en" profile={profile()} />);

    // The address is inside the anchor, so it is part of the link's accessible name.
    expect(
      screen.getByRole('link', { name: 'Instagram https://instagram.com/aysel' }),
    ).toBeInTheDocument();
  });

  it('renders an unknown platform under its stored name rather than as an empty label', () => {
    render(<ProfileAbout copy={COPY.about} locale="en" profile={profile({ socialLinks: [link({ platform: 'MASTODON' })] })} />);

    expect(screen.getByRole('link', { name: /MASTODON/u })).toHaveAttribute('rel', REL);
  });
});

describe('the location', () => {
  it('is printed, and is not a link, because ?city= is refused by the discovery service', () => {
    render(<ProfileAbout copy={COPY.about} locale="en" profile={profile()} />);

    expect(screen.getByText('Bakı')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Bakı' })).not.toBeInTheDocument();
  });

  it('is absent from the page for somebody who has not said where they are', () => {
    render(<ProfileAbout copy={COPY.about} locale="en" profile={profile({ location: null })} />);

    expect(screen.queryByText('Based in')).not.toBeInTheDocument();
  });
});

describe('the fields somebody has not filled in', () => {
  it('says the biography is empty rather than rendering a blank panel', () => {
    render(<ProfileAbout copy={COPY.about} locale="en" profile={profile({ bio: null })} />);

    expect(screen.getByText('Aysel has not written anything here.')).toBeInTheDocument();
  });

  it('renders no website row and no links section when there are none', () => {
    render(<ProfileAbout copy={COPY.about} locale="en" profile={profile({ websiteUrl: null, socialLinks: [] })} />);

    expect(screen.queryByText('Website')).not.toBeInTheDocument();
    expect(screen.queryByText('Elsewhere')).not.toBeInTheDocument();
    // The joined month is still there, so the panel is not empty.
    expect(screen.getByText('March 2025')).toBeInTheDocument();
  });
});

describe('the biography', () => {
  it('is text, so markup somebody typed stays text', () => {
    render(<ProfileAbout copy={COPY.about} locale="en" profile={profile({ bio: 'Visit <a href="https://evil.example">me</a>' })} />);

    expect(screen.getByText(/<a href="https:\/\/evil\.example">me<\/a>/u)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'me' })).not.toBeInTheDocument();
  });
});
