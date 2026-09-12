import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import en from '../../../messages/en.json';
import { LOCALE_COOKIE, SUPPORTED_LOCALES, type Locale } from '../../lib/i18n/locale';
import { LanguageSwitch } from './LanguageSwitch';
import { expectNoViolations } from '../../test-axe';

/**
 * The footer's language control — issue #458.
 *
 * WHAT THESE COVER, and each is a line of the issue:
 *
 *   - **the links go to the same page under another prefix**, not to that language's home. A
 *     switch that sent somebody to `/ru` asks them to find their way back to what they were
 *     reading, in a language they have just proved they were struggling with.
 *   - **every language is named in itself**, with its own `lang`. This is the control a
 *     stranded reader meets, and `Азербайджанский` is a dead end for exactly the person who
 *     needs it.
 *   - **the current language is marked with `aria-current`**, so the state is not carried by
 *     colour alone (docs/ui-kit.md §9.2).
 *   - **the cookie is still written**, for the one job `proxy.ts` left it: answering the bare
 *     path next time.
 *   - **no route changes from static to dynamic.** That is the issue's acceptance test, and
 *     it is checked here as a source scan, because the API that would break it —
 *     `useSearchParams` — opts a route out of static rendering without failing anything a
 *     rendered test can see.
 */

let pathname = '/discover';

vi.mock('next/navigation', async (importOriginal) => ({
  ...(await importOriginal<typeof import('next/navigation')>()),
  /*
   * The raw pathname, language and all, because that is what the browser hands
   * `src/i18n/navigation`'s `usePathname` — which strips it. Mocking the stripped value would
   * assert a state the application is never in.
   */
  usePathname: () => `/en${pathname === '/' ? '' : pathname}`,
  useParams: () => ({ locale: 'en' }),
}));

const ENDONYMS: Record<Locale, string> = {
  az: 'Azərbaycan dili',
  en: 'English',
  ru: 'Русский',
  tr: 'Türkçe',
};

afterEach(cleanup);

beforeEach(() => {
  pathname = '/discover';
  document.cookie = `${LOCALE_COOKIE}=; Path=/; Max-Age=0`;
});

function renderSwitch(current: Locale = 'en') {
  return render(<LanguageSwitch heading={en.shell.footer.languageHeading} current={current} />);
}

describe('the footer language control', () => {
  it('is a navigation landmark named by the word beside it', () => {
    renderSwitch();

    expect(
      screen.getByRole('navigation', { name: en.shell.footer.languageHeading }),
    ).toBeInTheDocument();
  });

  it('offers all four languages, each named in itself and carrying its own lang', () => {
    renderSwitch();
    const nav = screen.getByRole('navigation', { name: en.shell.footer.languageHeading });

    for (const locale of SUPPORTED_LOCALES) {
      const link = within(nav).getByRole('link', { name: ENDONYMS[locale] });

      expect(link, `${locale} is named in itself`).toBeInTheDocument();
      expect(link).toHaveAttribute('lang', locale);
      expect(link).toHaveAttribute('hreflang', locale);
    }
  });

  it('links to the same page under each prefix, not to that language’s home', () => {
    pathname = '/projects/7/edit/story';
    renderSwitch();

    for (const locale of SUPPORTED_LOCALES) {
      expect(screen.getByRole('link', { name: ENDONYMS[locale] })).toHaveAttribute(
        'href',
        `/${locale}/projects/7/edit/story`,
      );
    }
  });

  it('answers the site root without doubling the slash', () => {
    pathname = '/';
    renderSwitch();

    expect(screen.getByRole('link', { name: 'Русский' })).toHaveAttribute('href', '/ru');
  });

  it('marks the language being read, and does not leave that to colour', () => {
    renderSwitch('ru');

    expect(screen.getByRole('link', { name: 'Русский' })).toHaveAttribute('aria-current', 'true');

    for (const other of SUPPORTED_LOCALES.filter((locale) => locale !== 'ru')) {
      expect(screen.getByRole('link', { name: ENDONYMS[other] })).not.toHaveAttribute(
        'aria-current',
      );
    }
  });

  it('remembers the choice in the cookie, which is what answers the bare path next time', async () => {
    const user = userEvent.setup();
    renderSwitch();

    await user.click(screen.getByRole('link', { name: 'Azərbaycan dili' }));

    expect(document.cookie).toContain(`${LOCALE_COOKIE}=az`);
  });

  it('leaves no automatically detectable violation', async () => {
    const { container } = renderSwitch();

    await expectNoViolations(container);
  });
});

/**
 * §4.13's footer is on `/`, the category landings and every static page. #458's one hard
 * constraint is that reaching the control costs none of them their cached render.
 */
describe('the acceptance test: no route becomes dynamic', () => {
  const source = readFileSync(join(import.meta.dirname, 'LanguageSwitch.tsx'), 'utf8');
  const footer = readFileSync(join(import.meta.dirname, 'SiteFooter.tsx'), 'utf8');

  it('reads no search parameters', () => {
    /*
     * `useSearchParams` opts the route out of static rendering up to the nearest Suspense
     * boundary — silently, with nothing to see in a rendered tree and nothing thrown. On a
     * component in the footer of every page that is the whole site, so the control keeps the
     * path and drops the query string.
     */
    /*
     * The call rather than the word: the component's own docblock names the hook to explain
     * why it is absent, and a test that banned the name would ban the explanation with it.
     */
    expect(source).not.toMatch(/useSearchParams\s*\(/u);
    expect(source).not.toMatch(/import\s*\{[^}]*useSearchParams/u);
  });

  it('reads no cookie and no header at render time', () => {
    /*
     * The cookie is WRITTEN on a click and never read here. Reading one is what made a
     * language control impossible before #123, and `next/headers` would bring the whole
     * refusal back.
     */
    expect(source).not.toContain('next/headers');
    expect(source).not.toMatch(/currentLocaleCookie|readLocaleCookie/u);
  });

  it('leaves the footer itself a Server Component', () => {
    /*
     * Only the control is a client boundary. A `'use client'` on `SiteFooter` would ship the
     * whole footer's markup twice — once as HTML and once as the JavaScript to rebuild it — on
     * every route in the application.
     */
    expect(footer.startsWith("'use client'")).toBe(false);
    expect(footer).toContain('LanguageSwitch');
  });
});
