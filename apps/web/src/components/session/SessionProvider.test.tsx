import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { signOut as clearSession } from '../../lib/api/access-token';
import { fetchSession } from '../../lib/session/session';
import { SessionProvider, useSession } from './SessionProvider';

/**
 * The session bootstrap and the route guard — #267.
 *
 * WHAT THESE COVER, and why each is here rather than left to a reviewer:
 *
 *   - the three states. `unknown` is a state, and collapsing it into "signed out" is the bug
 *     it exists to prevent: the shell would offer Register to a signed-in reader on every page
 *     load and then swap it.
 *   - the guard redirects an anonymous reader away from a private route AND carries where they
 *     were, so the sign-in returns them to it.
 *   - the guard does NOT redirect while the answer is unknown, and does not redirect from a
 *     public route. The first would send every signed-in reader to the sign-in page on every
 *     load; the second would make the pre-launch page and the checkout unreachable for the
 *     audience they exist for.
 *   - **a service outage is not a sign-out.** `fetchSession` turns every "there is no session"
 *     answer into `null`, so a throw means a 500 or an unreachable service — and a guard that
 *     redirected on that would march somebody off the page they were reading because the API
 *     restarted.
 */

const replaced: string[] = [];
const pushed: string[] = [];
let pathname = '/';

vi.mock('next/navigation', async (importOriginal) => ({
  /*
   * Spread first so the real module's other exports survive. `i18n/navigation.ts`
   * builds its wrappers at import time and reads `redirect` and `permanentRedirect`
   * while doing so, and a factory that replaced the module wholesale left those
   * undefined — which failed as a TypeError inside next-intl rather than anywhere
   * near the test that caused it.
   */
  ...(await importOriginal<typeof import('next/navigation')>()),
  usePathname: () => pathname,
  useRouter: () => ({
    push: (href: string) => pushed.push(href),
    replace: (href: string) => replaced.push(href),
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => {},
  }),
}));

vi.mock('../../lib/session/session', () => ({ fetchSession: vi.fn() }));
vi.mock('../../lib/api/access-token', () => ({ signOut: vi.fn().mockResolvedValue(undefined) }));

const sessionMock = vi.mocked(fetchSession);
const clearMock = vi.mocked(clearSession);

const ACCOUNT = {
  id: 'ffa5a1e2-0000-7000-8000-000000000000',
  email: 'aysel@example.com',
  name: 'Aysel',
  slug: 'aysel',
  emailVerified: true,
};

/** Prints whatever the provider is currently saying, so a test can read it off the screen. */
function Probe() {
  const { status, session, signOut } = useSession();
  return (
    <div>
      <p data-testid="status">{status}</p>
      <p data-testid="name">{session?.name ?? '—'}</p>
      <button type="button" onClick={() => void signOut()}>
        Sign out
      </button>
    </div>
  );
}

function renderProvider() {
  return render(
    <SessionProvider>
      <Probe />
    </SessionProvider>,
  );
}

beforeEach(() => {
  replaced.length = 0;
  pushed.length = 0;
  pathname = '/';
  sessionMock.mockReset();
  clearMock.mockClear();
  window.history.replaceState(null, '', '/');
});

afterEach(cleanup);

describe('the bootstrap', () => {
  it('starts as unknown rather than guessing', () => {
    // Never settles, so the state under test is the one before the answer.
    sessionMock.mockReturnValue(new Promise(() => {}));
    renderProvider();

    expect(screen.getByTestId('status')).toHaveTextContent('unknown');
  });

  it('reads the account once and exposes it', async () => {
    sessionMock.mockResolvedValue(ACCOUNT);
    renderProvider();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('signed-in'));
    expect(screen.getByTestId('name')).toHaveTextContent('Aysel');
  });

  it('is signed out when there is no session', async () => {
    sessionMock.mockResolvedValue(null);
    renderProvider();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('signed-out'));
  });

  it('holds at unknown when the service fails, because an outage is not a sign-out', async () => {
    sessionMock.mockRejectedValue(new Error('the service is restarting'));
    renderProvider();

    // Give the rejection a turn of the loop to be handled before asserting nothing changed.
    await waitFor(() => expect(sessionMock).toHaveBeenCalled());
    expect(screen.getByTestId('status')).toHaveTextContent('unknown');
  });
});

describe('the route guard', () => {
  it('sends an anonymous reader away from a private route, carrying where they were', async () => {
    pathname = '/settings/sessions';
    window.history.replaceState(null, '', '/settings/sessions?tab=devices');
    sessionMock.mockResolvedValue(null);

    renderProvider();

    await waitFor(() =>
      expect(replaced).toEqual(['/en/sign-in?next=%2Fsettings%2Fsessions%3Ftab%3Ddevices']),
    );
  });

  it('replaces rather than pushes, so Back does not walk into the redirect again', async () => {
    pathname = '/settings';
    sessionMock.mockResolvedValue(null);

    renderProvider();

    await waitFor(() => expect(replaced).toHaveLength(1));
    expect(pushed).toHaveLength(0);
  });

  it('leaves a signed-in reader where they are', async () => {
    pathname = '/settings/sessions';
    sessionMock.mockResolvedValue(ACCOUNT);

    renderProvider();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('signed-in'));
    expect(replaced).toEqual([]);
  });

  it('does not redirect from a public route', async () => {
    pathname = '/discover';
    sessionMock.mockResolvedValue(null);

    renderProvider();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('signed-out'));
    expect(replaced).toEqual([]);
  });

  it('does not redirect while the answer is unknown', () => {
    pathname = '/settings';
    sessionMock.mockReturnValue(new Promise(() => {}));

    renderProvider();

    expect(replaced).toEqual([]);
  });
});

describe('signing out', () => {
  it('drops the local state first and ends on the home page', async () => {
    const user = userEvent.setup();
    sessionMock.mockResolvedValue(ACCOUNT);
    renderProvider();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('signed-in'));
    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('signed-out'));
    expect(clearMock).toHaveBeenCalled();
    expect(pushed).toContain('/en');
  });
});

describe('useSession outside a provider', () => {
  it('throws rather than answering a default', () => {
    // A default would be "signed out", and a component that silently renders its signed-out
    // branch because somebody forgot a provider is a defect that reaches production looking
    // like a design decision.
    expect(() => render(<Probe />)).toThrow(/outside <SessionProvider>/u);
  });
});
