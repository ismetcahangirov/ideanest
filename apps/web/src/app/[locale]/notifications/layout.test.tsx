import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { fetchSession } from '../../../lib/session/session';
import { SessionProvider } from '../../../components/session/SessionProvider';
import { MAIN_CONTENT_ID } from '../../../components/shell/SkipLink';
import NotificationsLayout from './layout';

/**
 * The inbox carries the site shell — issue #345.
 *
 * **This route is reached FROM the header.** `SiteHeader` draws a bell that links here, and
 * so do `AccountMenu` and `MobileNavDrawer`. A frame that disappears on arrival is therefore
 * not only a missing landmark, it is a control that appears to leave the site — and none of
 * that is visible in a screenshot of the inbox, because whoever takes one already knows where
 * they are. It is a structural question, so it is answered here.
 *
 * The three properties, and what each guards:
 *
 *   1. a `banner` and a `contentinfo` — the regression itself.
 *   2. exactly one `main` — the page declared its own before this layout existed, and a shell
 *      added above it without taking that away would trade a missing landmark for an
 *      ambiguous one. `MinimalShell.test.tsx` guards the same property on the other shell.
 *   3. the skip link points at that `main` — otherwise it is an anchor to nowhere.
 */

vi.mock('next/navigation', async (importOriginal) => ({
  /*
   * Spread first so the real module's other exports survive. `i18n/navigation.ts`
   * builds its wrappers at import time and reads `redirect` and `permanentRedirect`
   * while doing so, and a factory that replaced the module wholesale left those
   * undefined — which failed as a TypeError inside next-intl rather than anywhere
   * near the test that caused it.
   */
  ...(await importOriginal<typeof import('next/navigation')>()),
  usePathname: () => '/notifications',
  useRouter: () => ({
    push: () => {},
    replace: () => {},
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => {},
  }),
}));

vi.mock('../../../lib/session/session', () => ({ fetchSession: vi.fn() }));
vi.mock('../../../lib/api/access-token', () => ({ signOut: vi.fn().mockResolvedValue(undefined) }));

const sessionMock = vi.mocked(fetchSession);

beforeEach(() => {
  sessionMock.mockReset();
  sessionMock.mockResolvedValue(null);
});

afterEach(cleanup);

/*
 * A paragraph stands in for the page. `InboxPanel` reads the inbox with a bearer token and is
 * not what is under test; mounting it would make this a test of the fetch mocks. What matters
 * is what the layout puts around whatever it is given.
 */
function renderLayout() {
  return render(
    <SessionProvider>{NotificationsLayout({ children: <p>The inbox</p> })}</SessionProvider>,
  );
}

describe('the notifications page', () => {
  it('renders inside the site header and footer', () => {
    renderLayout();

    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
    expect(screen.getByText('The inbox')).toBeInTheDocument();
  });

  it('has exactly one main landmark, and the skip link points at it', () => {
    renderLayout();

    const mains = screen.getAllByRole('main');
    expect(mains).toHaveLength(1);
    expect(mains[0]).toHaveAttribute('id', MAIN_CONTENT_ID);

    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute(
      'href',
      `#${MAIN_CONTENT_ID}`,
    );
  });
});
