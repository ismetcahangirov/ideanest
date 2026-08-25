import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { CAMPAIGN_TABS } from '../../lib/projects/tabs';
import { CampaignTabs } from './CampaignTabs';
import CATALOGUE from '../../../messages/en.json';
import { resolveServerTree } from '../../test-support/server-tree';

/*
 * The real catalogue, through next-intl's own formatter.
 *
 * `createTranslator` rather than a hand-rolled substitution, because these messages carry ICU
 * plurals — `{days, plural, one {# day left} other {# days left}}` — and a regex that swapped
 * `{days}` for a number would produce a sentence no language actually renders. Asserting
 * against `messages/en.json` formatted the way the application formats it is what makes this
 * suite fail when a translation is edited to something the component no longer draws.
 */
vi.mock('next-intl/server', async () => {
  const { createTranslator } = await import('next-intl');

  return {
    getLocale: async () => 'en',
    /*
     * `namespace` is a plain string here and a union of every valid path in next-intl's own
     * types. The cast is at the mock's edge rather than at each call: what a component asks
     * for is whatever it asks for, and a namespace that does not exist fails as a missing
     * message — which is the failure worth seeing.
     */
    getTranslations: async (namespace: string) =>
      createTranslator({
        locale: 'en',
        messages: CATALOGUE,
        namespace: namespace as never,
      }),
  };
});



/**
 * §4.4's tab strip — #282, #283, #284, #285.
 *
 * WHAT THESE COVER:
 *
 *   - **the FAQ tab is in the strip and it is a link to `?tab=faq`.** #283's tab is only
 *     reachable if it is an address: a crawler follows a link, a reader can send one, and
 *     somebody whose JavaScript never arrives can still open it.
 *   - **this is a list of links and NOT an ARIA tab widget** (docs/architecture.md §4.4).
 *     `role="tab"` promises arrow-key movement and a panel that changes without navigating,
 *     and both would be lies here. A widget whose roles promise behaviour it does not have is
 *     worse than no roles at all, so the test asserts the roles are absent rather than
 *     present.
 *   - **the current tab is marked in more than a colour.** `aria-current="page"` carries it to
 *     a screen reader and a weight change carries it to somebody who cannot separate two greys
 *     (docs/ui-kit.md §9.2).
 *   - **the default tab is the bare path.** `?tab=campaign` and the bare path are the same
 *     page, and the moment the strip produces both, one campaign has two addresses.
 *   - **the row cannot wrap.** Five labels do not fit across a phone, and a wrapped second row
 *     would push the campaign's content down by a line on exactly the narrow viewports where
 *     vertical space is scarcest — so the row scrolls instead.
 */

const PATH = '/projects/ayan/coffee-table-book';

afterEach(cleanup);

describe('the campaign tab strip', () => {
  it('offers the FAQ tab as a link to ?tab=faq', async () => {
    render(await resolveServerTree(<CampaignTabs active="campaign" path={PATH} />));

    expect(screen.getByRole('link', { name: 'FAQ' })).toHaveAttribute(
      'href',
      `/en${PATH}?tab=faq`,
    );
  });

  it('publishes every tab the module declares, in that order', async () => {
    render(await resolveServerTree(<CampaignTabs active="campaign" path={PATH} />));

    const names = within(screen.getByRole('navigation', { name: 'Campaign sections' }))
      .getAllByRole('link')
      .map((link) => link.textContent);
    expect(names).toEqual(CAMPAIGN_TABS.map((tab) => tab.label));
    expect(names).toContain('FAQ');
  });

  it('gives the default tab the bare path, so one campaign has one address', async () => {
    render(await resolveServerTree(<CampaignTabs active="faq" path={PATH} />));

    expect(screen.getByRole('link', { name: 'Campaign' })).toHaveAttribute('href', `/en${PATH}`);
  });

  it('marks the tab being read in words rather than in colour alone', async () => {
    render(await resolveServerTree(<CampaignTabs active="faq" path={PATH} />));

    expect(screen.getByRole('link', { name: 'FAQ' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Updates' })).not.toHaveAttribute('aria-current');
  });

  /**
   * docs/architecture.md §4.4 states this outright: the strip is a list of links and
   * deliberately not an ARIA tab widget, because activating one navigates.
   */
  it('is navigation, not a tab widget', async () => {
    render(await resolveServerTree(<CampaignTabs active="campaign" path={PATH} />));

    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(screen.queryAllByRole('tab')).toHaveLength(0);
    expect(screen.getByRole('navigation', { name: 'Campaign sections' })).toBeInTheDocument();
  });

  /**
   * Five tabs no longer fit across a phone. The row must therefore scroll — a second row
   * would move the campaign's own content down by a line on the narrowest viewports.
   */
  it('keeps the five tabs on one scrollable row rather than wrapping', async () => {
    const { container } = render(await resolveServerTree(<CampaignTabs active="campaign" path={PATH} />));

    const row = container.querySelector('ul');
    expect(row).toHaveClass('overflow-x-auto');
    expect(row?.className).not.toContain('flex-wrap');

    for (const link of screen.getAllByRole('link')) {
      expect(link).toHaveClass('whitespace-nowrap');
    }
  });
});
