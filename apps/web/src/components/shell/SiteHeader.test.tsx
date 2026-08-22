import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fetchSession } from '../../lib/session/session';
import { SessionProvider } from '../session/SessionProvider';
import { SiteHeader } from './SiteHeader';

/**
 * The global header — §4.13 WS-01, docs/ui-kit.md §8.6.
 *
 * WHAT THESE COVER:
 *
 *   - the three session states, and in particular that the third one shows NEITHER action
 *     pair. A header that guessed would offer Register to a signed-in reader on every page
 *     load and then swap it, which reads as the site logging them out.
 *   - §8.6's lime rule: exactly one lime element, only when signed out, and it is the primary
 *     action. A second one is a design regression that no screenshot review reliably catches.
 *   - `aria-current="page"` carries which section is being read, because §9.2 forbids colour
 *     (or an underline) from carrying it alone.
 *   - every icon-only control has an accessible name (§9.4).
 */

let pathname = '/';

vi.mock('next/navigation', () => ({
  usePathname: () => pathname,
  useRouter: () => ({
    push: () => {},
    replace: () => {},
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => {},
  }),
}));

vi.mock('../../lib/session/session', () => ({ fetchSession: vi.fn() }));
vi.mock('../../lib/api/access-token', () => ({ signOut: vi.fn().mockResolvedValue(undefined) }));

const sessionMock = vi.mocked(fetchSession);

const ACCOUNT = {
  id: 'ffa5a1e2-0000-7000-8000-000000000000',
  email: 'aysel@example.com',
  name: 'Aysel Quliyeva',
  slug: 'aysel',
  emailVerified: true,
};

function renderHeader() {
  return render(
    <SessionProvider>
      <SiteHeader />
    </SessionProvider>,
  );
}

beforeEach(() => {
  pathname = '/';
  sessionMock.mockReset();
  sessionMock.mockResolvedValue(null);
});

afterEach(cleanup);

describe('while the session is unknown', () => {
  it('offers neither action pair', () => {
    sessionMock.mockReturnValue(new Promise(() => {}));
    renderHeader();

    expect(screen.queryByRole('link', { name: 'Sign in' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Register' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Aysel/u })).toBeNull();
  });
});

describe('signed out', () => {
  it('offers sign in and register', async () => {
    renderHeader();

    await waitFor(() => expect(screen.getByRole('link', { name: 'Register' })).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in');
  });

  /**
   * §8.6: "the header's only lime element is the primary action a signed-out visitor is being
   * asked to take, and there is at most one of them". `data-on-lime` is the marker that also
   * flips the focus ring to near-black (§9.3), so counting it counts both facts at once.
   */
  it('spends its one lime element on Register and nothing else', async () => {
    const { container } = renderHeader();

    await waitFor(() => expect(screen.getByRole('link', { name: 'Register' })).toBeInTheDocument());

    const onLime = container.querySelectorAll('[data-on-lime]');
    expect(onLime).toHaveLength(1);
    expect(onLime[0]).toHaveAttribute('href', '/register');
  });

  it('is a link and not a button, so it can be opened in a new tab', async () => {
    renderHeader();
    await waitFor(() =>
      expect(screen.getByRole('link', { name: 'Register' })).toHaveAttribute('href', '/register'),
    );
  });
});

describe('signed in', () => {
  beforeEach(() => {
    sessionMock.mockResolvedValue(ACCOUNT);
  });

  it('names the account and drops the signed-out pair', async () => {
    renderHeader();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Aysel Quliyeva/u })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('link', { name: 'Register' })).toBeNull();
  });

  it('has no lime element at all, because nothing is being asked of the reader', async () => {
    const { container } = renderHeader();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Aysel Quliyeva/u })).toBeInTheDocument(),
    );
    expect(container.querySelectorAll('[data-on-lime]')).toHaveLength(0);
  });

  it('names its icon-only notifications control', async () => {
    renderHeader();

    await waitFor(() =>
      expect(screen.getByRole('link', { name: 'Notifications' })).toHaveAttribute(
        'href',
        '/notifications',
      ),
    );
  });

  it('opens the account menu and closes it on Escape', async () => {
    const user = userEvent.setup();
    renderHeader();

    const trigger = await screen.findByRole('button', { name: /Aysel Quliyeva/u });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    await user.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('link', { name: 'Devices and sessions' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(trigger).toHaveAttribute('aria-expanded', 'false'));
  });

  it('says so when the address is not verified, because nowhere else would', async () => {
    const user = userEvent.setup();
    sessionMock.mockResolvedValue({ ...ACCOUNT, emailVerified: false });
    renderHeader();

    await user.click(await screen.findByRole('button', { name: /Aysel Quliyeva/u }));

    expect(screen.getByText(/not verified yet/u)).toBeInTheDocument();
  });

  it('says nothing about verification once the address is verified', async () => {
    const user = userEvent.setup();
    renderHeader();

    await user.click(await screen.findByRole('button', { name: /Aysel Quliyeva/u }));

    expect(screen.queryByText(/not verified yet/u)).toBeNull();
  });
});

describe('the current section', () => {
  it('is carried by aria-current rather than by the underline alone', async () => {
    pathname = '/categories/games/tabletop';
    renderHeader();

    const nav = screen.getByRole('link', { name: 'Categories' });
    expect(nav).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Discover' })).not.toHaveAttribute('aria-current');
  });
});

describe('the search entry', () => {
  it('is a labelled search form, so it is reachable by landmark', async () => {
    renderHeader();

    const searches = screen.getAllByRole('search');
    expect(searches.length).toBeGreaterThan(0);
    expect(
      within(searches[0] as HTMLElement).getByRole('searchbox', { name: 'Search campaigns' }),
    ).toBeInTheDocument();
  });
});
