import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import az from '../../../messages/az.json';
import en from '../../../messages/en.json';
import ru from '../../../messages/ru.json';
import tr from '../../../messages/tr.json';
import { SUPPORTED_LOCALES, type Locale } from '../../lib/i18n/locale';
import { FOOTER_GROUPS } from './navigation';
import { SiteFooter } from './SiteFooter';
import { expectNoViolations } from '../../test-axe';

/**
 * The global footer — §4.13 WS-02, and since #324 the first shell surface drawn from the
 * catalogue.
 *
 * WHAT THESE COVER:
 *
 *   - it is a labelled landmark, so it is reachable without scrolling to it.
 *   - every group in `navigation.ts` is rendered **in each of the four languages**, so a link
 *     added there cannot be silently dropped by this component and a key added there cannot
 *     ship with three languages translated.
 *   - the language is OFFERED since #458, and every language is still named in itself. It
 *     was the constant `'English'` before #123, a statement of the reader's own language
 *     after it, and a control since a locale-prefixed URL made one possible without turning
 *     a cached page into a render per visitor. `LanguageSwitch.test.tsx` covers the control
 *     itself; what is asserted here is that the footer carries it and that the four links
 *     keep the page the reader is on.
 *   - currency is STATED and not offered, and #458 did not change that. A display currency is
 *     a per-reader preference with nothing in the URL to carry it, so a control here would
 *     have to know who is reading — the dynamic render the language control was careful not
 *     to reintroduce.
 *   - there is no legal column, because §22 has not written the pages and #293 is
 *     `status: needs-decision`. A Terms link resolving to a 404 is a promise about a document
 *     that does not exist.
 *   - the copyright line carries no year, because a year built from the clock differs between
 *     the server render and the browser and goes stale on a statically rendered page.
 */

const CATALOGUES: Record<Locale, typeof en> = { az, en, ru, tr };

/** Swapped per render, then read by the mocked server helpers below. */
let locale: Locale = 'en';

function messageAt(namespace: string, key: string): string {
  const path = `${namespace}.${key}`;
  let node: unknown = CATALOGUES[locale];

  for (const segment of path.split('.')) {
    if (typeof node !== 'object' || node === null) throw new Error(`no message at ${path}`);
    node = (node as Record<string, unknown>)[segment];
  }

  if (typeof node !== 'string') throw new Error(`no message at ${path} in ${locale}`);
  return node;
}

/*
 * The real catalogue, reached the way `i18n/request.ts` would reach it. Asserting against
 * `messages/*.json` rather than against words typed into this file is what makes the suite
 * fail when a translation is edited to something the component no longer renders — a test
 * holding its own copy of the words passes whatever the catalogue says.
 */
vi.mock('next-intl/server', () => ({
  getLocale: async () => locale,
  getTranslations: async (namespace: string) => (key: string) => messageAt(namespace, key),
}));

/*
 * `useParams` is mocked from the same `locale` the catalogue is read from, because in the
 * running application the two have one source: the route's own `[locale]` segment feeds
 * `getTranslations` on the server and `Link` in the browser. Mocking only one of them would
 * assert a state the application cannot be in — Turkish words on English hrefs — and would
 * pass while the footer linked every reader out of their language.
 */
vi.mock('next/navigation', async (importOriginal) => ({
  ...(await importOriginal<typeof import('next/navigation')>()),
  useParams: () => ({ locale }),
  /*
   * A page inside the site, so the language links have something to keep. `LanguageSwitch`
   * reads this through `src/i18n/navigation`'s `usePathname`, which takes the language off
   * again — which is why the mock carries one.
   */
  usePathname: () => `/${locale}/discover`,
}));

afterEach(cleanup);

async function renderFooter(at: Locale = 'en') {
  locale = at;
  return render(await SiteFooter());
}

describe('the footer', () => {
  it('is a labelled landmark with a named navigation inside it', async () => {
    await renderFooter();

    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
    expect(
      screen.getByRole('navigation', { name: en.shell.footer.label }),
    ).toBeInTheDocument();
  });

  it.each(SUPPORTED_LOCALES)(
    'renders every group and every link the navigation model declares, in %s',
    async (at) => {
      const { unmount } = await renderFooter(at);
      const catalogue = CATALOGUES[at];
      const nav = screen.getByRole('navigation', { name: catalogue.shell.footer.label });

      for (const group of FOOTER_GROUPS) {
        const groups = catalogue.shell.footer.groups;
        const heading = groups[group.headingKey as keyof typeof groups];

        expect(
          within(nav).getByRole('heading', { name: heading }),
          `${at} draws ${group.headingKey}`,
        ).toBeInTheDocument();

        for (const link of group.links) {
          const links = catalogue.shell.footer.links;
          const label = links[link.key as keyof typeof links];

          expect(
            within(nav).getByRole('link', { name: label }),
            `${at} draws ${link.key}`,
          ).toHaveAttribute('href', `/${at}${link.href}`);
        }
      }

      unmount();
    },
  );

  it('leaves no key unresolved in any language', async () => {
    /*
     * `getMessageFallback` renders a missing key's own name in production rather than taking
     * the route down, so an untranslated footer link reads `shell.footer.links.press` on the
     * page. That is a defect nobody reports, because the person who would notice it is
     * reading a language nobody on the team reads.
     */
    for (const at of SUPPORTED_LOCALES) {
      const { container, unmount } = await renderFooter(at);

      expect(container.textContent ?? '', `unresolved key in ${at}`).not.toMatch(/shell\./u);

      unmount();
    }
  });

  it('says what the platform is, including the fact people most often assume wrongly', async () => {
    await renderFooter();

    expect(screen.getByText(en.shell.tagline)).toBeInTheDocument();
  });

  /*
   * Not "Russian" but "Русский". A reader scanning the bottom of the page for their own
   * language recognises the endonym; the English name is a word they may not read. It also has
   * to follow the route rather than a build-time constant, which is what the line was before
   * #123.
   */
  const NAMES: Record<Locale, string> = {
    az: 'Azərbaycan dili',
    en: 'English',
    ru: 'Русский',
    tr: 'Türkçe',
  };

  it.each(SUPPORTED_LOCALES)('marks the language being read, in itself (%s)', async (at) => {
    const { unmount } = await renderFooter(at);

    expect(screen.getByRole('link', { name: NAMES[at] })).toHaveAttribute('aria-current', 'true');

    unmount();
  });

  it.each(SUPPORTED_LOCALES)('offers the other three as a way out of %s', async (at) => {
    /*
     * ISSUE #458. The reader this matters to is signed out: `/settings/language` is inside the
     * account area, so before this the only way out of a language somebody could not read was
     * editing the address bar. Each link keeps the page rather than going to that language's
     * home.
     */
    const { unmount } = await renderFooter(at);

    for (const locale of SUPPORTED_LOCALES) {
      expect(
        screen.getByRole('link', { name: NAMES[locale] }),
        `${at} offers ${locale}`,
      ).toHaveAttribute('href', `/${locale}/discover`);
    }

    unmount();
  });

  it('states the currency rather than offering a control', async () => {
    await renderFooter();

    expect(screen.getByText(en.shell.footer.currencyValue)).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();

    /* And no link out of it either — the four in the footer's bottom row are all languages. */
    expect(screen.queryByRole('link', { name: /manat|AZN/iu })).toBeNull();
  });

  it('offers no legal links, because the documents do not exist', async () => {
    await renderFooter();

    expect(screen.queryByRole('link', { name: /terms/iu })).toBeNull();
    expect(screen.queryByRole('link', { name: /privacy/iu })).toBeNull();
    expect(screen.queryByRole('link', { name: /cookie/iu })).toBeNull();
  });

  it('claims no year', async () => {
    await renderFooter();

    expect(screen.getByText('© IdeaNest')).toBeInTheDocument();
    expect(screen.queryByText(/©.*20\d\d/u)).toBeNull();
  });
});

describe('accessibility', () => {
  /** #129. Four link groups, each of which needs to be a named list rather than a heap. */
  it('leaves no automatically detectable violation', async () => {
    const { container } = render(await SiteFooter());

    await expectNoViolations(container);
  });
});
