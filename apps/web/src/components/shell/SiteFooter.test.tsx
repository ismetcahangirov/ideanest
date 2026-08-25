import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { FOOTER_GROUPS } from './navigation';
import { SiteFooter } from './SiteFooter';

/**
 * The global footer — §4.13 WS-02.
 *
 * WHAT THESE COVER:
 *
 *   - it is a labelled landmark, so it is reachable without scrolling to it.
 *   - every group in `navigation.ts` is rendered, so a link added there cannot be silently
 *     dropped by this component.
 *   - language and currency are STATED and not offered. #280 is blocked, and a `<select>` that
 *     changed nothing would be a control that lies — the worst of the three options available.
 *   - there is no legal column, because §22 has not written the pages and #293 is
 *     `status: needs-decision`. A Terms link resolving to a 404 is a promise about a document
 *     that does not exist.
 *   - the copyright line carries no year, because a year built from the clock differs between
 *     the server render and the browser and goes stale on a statically rendered page.
 */

afterEach(cleanup);

describe('the footer', () => {
  it('is a labelled landmark with a named navigation inside it', () => {
    render(<SiteFooter />);

    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Footer' })).toBeInTheDocument();
  });

  it('renders every group and every link the navigation model declares', () => {
    render(<SiteFooter />);
    const nav = screen.getByRole('navigation', { name: 'Footer' });

    for (const group of FOOTER_GROUPS) {
      expect(within(nav).getByRole('heading', { name: group.heading })).toBeInTheDocument();
      for (const link of group.links) {
        expect(within(nav).getByRole('link', { name: link.label })).toHaveAttribute(
          'href',
          `/en${link.href}`,
        );
      }
    }
  });

  it('says what the platform is, including the fact people most often assume wrongly', () => {
    render(<SiteFooter />);
    expect(screen.getByText(/nobody is charged unless a campaign reaches its goal/iu)).toBeInTheDocument();
  });

  it('states the language and the currency rather than offering a control', () => {
    render(<SiteFooter />);

    expect(screen.getByText('English')).toBeInTheDocument();
    expect(screen.getByText('Azerbaijani manat (AZN)')).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('offers no legal links, because the documents do not exist', () => {
    render(<SiteFooter />);

    expect(screen.queryByRole('link', { name: /terms/iu })).toBeNull();
    expect(screen.queryByRole('link', { name: /privacy/iu })).toBeNull();
    expect(screen.queryByRole('link', { name: /cookie/iu })).toBeNull();
  });

  it('claims no year', () => {
    render(<SiteFooter />);

    expect(screen.getByText('© IdeaNest')).toBeInTheDocument();
    expect(screen.queryByText(/©.*20\d\d/u)).toBeNull();
  });
});
