'use client';

import { localeHref, usePathname } from '../../i18n/navigation';
import { writeLocaleCookie } from '../../lib/i18n/cookie';
import { LOCALE_NAMES, SUPPORTED_LOCALES, type Locale } from '../../lib/i18n/locale';

/**
 * The language control a signed-out visitor can actually reach — issue #458.
 *
 * <h2>Why this exists now and did not before</h2>
 *
 * The only way to change language used to be `/settings/language`, which is inside the
 * account area. So the reader most likely to need it — somebody who landed on the English
 * default on a platform whose primary language is Azerbaijani — was the one least able to
 * reach it, and the only route left was editing the address bar.
 *
 * `SiteFooter` refused to offer one, and its docblock was explicit about the reason:
 * choosing a language meant reading a cookie, and reading a cookie makes a render dynamic —
 * on `/`, on the category landings and on every static page. #123 removed the premise rather
 * than the cost. The language is a path segment now, so switching is a link from one cached
 * address to another and nothing is read at render time. `src/i18n/request.ts` states the
 * consequence: "there is no longer a performance argument for leaving any surface in
 * English."
 *
 * <h2>Links to the same page, not to that language's home</h2>
 *
 * `usePathname` here is `src/i18n`'s, which gives the path with the language already taken
 * off — `/az/projects/7/edit/story` arrives as `/projects/7/edit/story`. Each link then puts
 * a different language back on. A switch that sent somebody to `/ru` would be asking them to
 * find their way back to what they were reading, in a language they have just proved they
 * were struggling with.
 *
 * <h2>`localeHref` and a plain anchor, which is the exception the guard allows</h2>
 *
 * `Link` from `src/i18n/navigation` keeps the CURRENT language, which is exactly wrong here:
 * it would render four links to the page already being read. `navigation.guard.test.ts`
 * permits an `<a>` at an application path provided it prefixes the path itself, and that is
 * what these do.
 *
 * <p>A full document load is also the right navigation. `<html lang>` is set by
 * `app/[locale]/layout.tsx` from the route's own segment, and a client-side transition is not
 * reliable about re-applying an attribute on the document element — a page whose words are
 * Russian and whose `lang` still says `en` tells a screen reader to pronounce them with
 * English phonetics, which is the defect #123 fixed and not one to reintroduce here.
 *
 * <h2>The cookie is still written</h2>
 *
 * Not for this render — the URL decides that — but for the one job `proxy.ts` left it:
 * answering the bare path next time. Somebody who arrives at `ideanest.az` with no language
 * on it should land in the one they chose, on this visit and on the next device-less return
 * to the domain.
 *
 * <h2>Each language is named in itself</h2>
 *
 * `LOCALE_NAMES`, never translated into the language the page happens to be drawn in.
 * `LanguagePanel` argues it at length and the argument is sharper here, because this control
 * is the one a stranger meets: somebody who landed in a language they cannot read finds
 * `Азербайджанский` a dead end and `Azərbaycan dili` a way out. Each anchor carries its own
 * `lang`, so a screen reader pronounces `Русский` with Russian phonemes instead of reading it
 * as mangled English, and `hrefLang` says the same thing about what is on the other end.
 *
 * <h2>The current language is not marked by colour</h2>
 *
 * `aria-current="true"` carries it, and docs/ui-kit.md §9.2 is why: a white link among three
 * grey ones says nothing to somebody who cannot see the difference. The colour follows the
 * state rather than being it.
 *
 * <h2>Motion</h2>
 *
 * Colour only, 150ms, like every other link in the footer. The shell's motion budget is
 * `docs/motion-system.md` §5's single row — §4.7's collapse and nothing else — and this
 * spends none of it.
 */
export interface LanguageSwitchProps {
  /** The visible word this control is labelled by — `shell.footer.languageHeading`. */
  readonly heading: string;
  /** The language the server drew the page in, from the route's own segment. */
  readonly current: Locale;
}

export function LanguageSwitch({ heading, current }: LanguageSwitchProps) {
  /*
   * The path without its language. No search parameters: `useSearchParams` opts a route out
   * of static rendering up to the nearest Suspense boundary, and this component is in the
   * footer of every page on the site — the whole point of #458 is that reaching the control
   * costs no route its cached render. A language switch that dropped a filter would be worth
   * the trade; one that turned `/` dynamic to keep a query string is not.
   */
  const pathname = usePathname();

  return (
    <nav aria-labelledby="footer-language" className="flex flex-wrap items-center gap-x-3 gap-y-2">
      <span id="footer-language">{heading}</span>

      <ul className="flex list-none flex-wrap items-center gap-x-3 gap-y-1">
        {SUPPORTED_LOCALES.map((locale) => {
          const reading = locale === current;

          return (
            <li key={locale}>
              <a
                href={localeHref(pathname, locale)}
                lang={locale}
                hrefLang={locale}
                aria-current={reading ? 'true' : undefined}
                /*
                 * The write happens before the browser leaves the page, which is what makes a
                 * plain handler enough — no effect, no state, nothing to keep in sync. A
                 * middle-click or "open in new tab" skips it and still arrives in the right
                 * language, because the language is in the address.
                 */
                onClick={() => writeLocaleCookie(locale)}
                className={
                  reading
                    ? 'rounded-sm text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]'
                    : 'rounded-sm text-white/64 transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]'
                }
              >
                {LOCALE_NAMES[locale]}
              </a>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
