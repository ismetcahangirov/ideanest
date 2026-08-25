import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { fetchSession } from '../../../../lib/session/session';
import { SessionProvider } from '../../../../components/session/SessionProvider';
import { EditorShell } from '../../../../components/campaign-editor/EditorShell';
import { MAIN_CONTENT_ID } from '../../../../components/shell/SkipLink';
import CampaignEditorLayout from './layout';
import NewProjectLayout from '../../new/layout';

/**
 * The campaign editor and the create form carry the site shell — issue #347.
 *
 * <h2>The landmark assertion is the one that earns this file</h2>
 *
 * `EditorShell` renders a `<header>` of its own — the campaign's title, its state and the
 * save indicator. HTML gives that element the `banner` role **unless it is descended from
 * `main`**, and until #347 it was, because every tab wrapped it in a `<main>` of its own.
 * Putting a shell above the editor and leaving those in place would have produced two
 * `main`s and two `banner`s at once, and neither is visible in a screenshot: a page with two
 * banners tells a screen-reader user there are two site headers to choose between.
 *
 * So the real editor is rendered here rather than a stand-in. A test that mounted a
 * paragraph would pass whether or not the header nesting was right, which is the only thing
 * this arrangement can get wrong.
 *
 * <h2>Motion is not asserted, and that is deliberate</h2>
 *
 * `docs/motion-system.md` §5 gives the editor "None — autosave indicator only" and gives the
 * shell "One — §4.7's collapse". The layout's docblock argues why those coexist; the
 * argument is about which row of a table the collapse is charged to, and a test cannot check
 * a budget's bookkeeping. Storybook and review own that.
 */

vi.mock('next/navigation', () => ({
  usePathname: () => '/projects/p1/edit/basics',
  useRouter: () => ({
    push: () => {},
    replace: () => {},
    prefetch: () => {},
    back: () => {},
    forward: () => {},
    refresh: () => {},
  }),
}));

vi.mock('../../../../lib/session/session', () => ({ fetchSession: vi.fn() }));
vi.mock('../../../../lib/api/access-token', () => ({
  signOut: vi.fn().mockResolvedValue(undefined),
}));

const sessionMock = vi.mocked(fetchSession);

beforeEach(() => {
  sessionMock.mockReset();
  sessionMock.mockResolvedValue(null);
});

afterEach(cleanup);

function renderInLayout(Layout: (props: { children: ReactNode }) => ReactNode, body: ReactNode) {
  return render(<SessionProvider>{Layout({ children: body })}</SessionProvider>);
}

describe('the campaign editor', () => {
  const editor = (
    <EditorShell projectId="p1" active="basics" title="A solar lamp" state="DRAFT">
      <p>The basics form</p>
    </EditorShell>
  );

  it('renders inside the site header and footer', () => {
    renderInLayout(CampaignEditorLayout, editor);

    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
    expect(screen.getByText('The basics form')).toBeInTheDocument();
    // The editor's own frame is still there and still names the campaign.
    expect(screen.getByRole('heading', { level: 1, name: 'A solar lamp' })).toBeInTheDocument();
  });

  it("keeps EditorShell's header out of the banner role by nesting it inside main", () => {
    renderInLayout(CampaignEditorLayout, editor);

    // One site header, not two. `getByRole` throws on more than one match, so this is the
    // assertion — the editor's `<header>` must stay generic.
    const banners = screen.getAllByRole('banner');
    expect(banners).toHaveLength(1);

    const mains = screen.getAllByRole('main');
    expect(mains).toHaveLength(1);
    expect(mains[0]).toHaveAttribute('id', MAIN_CONTENT_ID);

    // The one banner is the site header, OUTSIDE main. The editor's frame is inside it,
    // which is exactly what keeps `EditorShell`'s `<header>` generic.
    expect(mains[0]).not.toContainElement(banners[0] ?? null);
    expect(mains[0]).toContainElement(screen.getByRole('heading', { level: 1 }));
  });
});

describe('the create-a-campaign form', () => {
  it('renders inside the site header and footer, with one main', () => {
    renderInLayout(NewProjectLayout, <p>Give it a working title</p>);

    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();

    const mains = screen.getAllByRole('main');
    expect(mains).toHaveLength(1);
    expect(mains[0]).toHaveAttribute('id', MAIN_CONTENT_ID);
  });
});
