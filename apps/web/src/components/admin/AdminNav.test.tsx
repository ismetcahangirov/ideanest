import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { CONSOLE_GROUPS } from '../../lib/admin/navigation';
import { adminShellCopyFrom } from '../../lib/i18n/admin-copy';
import { translatorFor } from '../../test-copy';
import { AdminNav } from './AdminNav';

/**
 * The console's rail reaches its own bottom.
 *
 * <p>`sticky` pins the rail below the header and keeps it there while the page moves, which
 * is the point of it; the consequence was that a rail taller than the viewport had a bottom
 * nobody could reach — scrolling the page moved the screen on the right and left the rail
 * exactly where it was. `AccountNav` hit this with thirteen destinations in #349. This one
 * has twenty-six across six groups, so the last group or two were unreachable on an ordinary
 * viewport rather than at a particular zoom level.
 *
 * <p>Asserting on class names is not something this codebase does often, and it is right
 * here for the reason it is elsewhere rare: the behaviour is a layout property that jsdom
 * does not implement, so there is nothing else to observe. What the assertions pin is the
 * *pair* — a scroll container and the height that bounds it — because either alone is the
 * defect. A `max-height` with no `overflow-y` clips the rail instead of scrolling it, and an
 * `overflow-y` with no bound never scrolls at all.
 */

vi.mock('next/navigation', async (importOriginal) => ({
  /*
   * Spread first so the real module's other exports survive: `i18n/navigation.ts` builds its
   * wrappers at import time and reads `redirect` while doing so, and a factory that replaced
   * the module wholesale left those undefined.
   */
  ...(await importOriginal<typeof import('next/navigation')>()),
  usePathname: () => '/admin/audit',
}));

const COPY = adminShellCopyFrom(translatorFor('admin'));

describe('the console rail', () => {
  it('is its own scroll container above the breakpoint, bounded by the viewport', () => {
    render(<AdminNav copy={COPY} />);

    const rail = screen.getByRole('navigation', { name: COPY.navLabel });

    // `100dvh` and not `100vh`: the dynamic unit tracks a collapsing browser toolbar, and
    // `vh` would size the rail to a viewport the reader does not have.
    expect(rail.className).toContain('lg:max-h-[calc(100dvh-4rem)]');
    expect(rail.className).toContain('lg:overflow-y-auto');
    // Without `overscroll-contain`, reaching the end of the rail hands the wheel to the page
    // and scrolls the screen nobody was looking at.
    expect(rail.className).toContain('lg:overscroll-contain');
  });

  it('keeps room for the focus ring the scroll container would otherwise clip', () => {
    render(<AdminNav copy={COPY} />);

    const rail = screen.getByRole('navigation', { name: COPY.navLabel });

    // The links take `outline-2 outline-offset-2` — four pixels outside their own box — and
    // docs/ui-kit.md §9.3 requires that ring visible on every interactive element. The
    // negative margin gives the padding back, so nothing moves.
    expect(rail.className).toContain('lg:px-1');
    expect(rail.className).toContain('lg:-mx-1');
  });

  it('still draws every destination, which is what made the scroll necessary', () => {
    render(<AdminNav copy={COPY} />);

    const destinations = CONSOLE_GROUPS.flatMap((group) => group.links);

    // If this count ever drops, the rail got shorter and somebody should say why rather
    // than the scroll quietly becoming decoration.
    expect(new Set(destinations).size).toBeGreaterThanOrEqual(26);
    for (const link of destinations) {
      expect(screen.getByRole('link', { name: COPY.links[link] })).toBeInTheDocument();
    }
  });
});
