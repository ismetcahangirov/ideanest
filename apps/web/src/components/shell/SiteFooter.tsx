import { Link } from '../../i18n/navigation';
import { getLocale } from 'next-intl/server';
import { localeOrDefault } from '../../lib/i18n/locale';
import { footerCopy } from '../../lib/i18n/shell-copy.server';
import { LanguageSwitch } from './LanguageSwitch';

/**
 * The global footer — §4.13 WS-02, docs/ui-kit.md §8.6.
 *
 * <h2>A Server Component, and it has to stay one</h2>
 *
 * No state, no handler, no hook. It is on every page in the site shell, so anything that
 * made it a client boundary would ship its markup twice — once as HTML and once as the
 * JavaScript to rebuild it — on every route in the application. Nothing here is imported
 * from `@ideanest/ui`'s root barrel for the same reason `app/discover/page.tsx` gives: the
 * barrel reaches `createContext` and the build refuses the route.
 *
 * <h2>The page ground, not a card</h2>
 *
 * §8.6: `--surface-1` with a `--divider` rule above it. A `--surface-2` footer reads as a
 * panel with content in it and pulls the eye down at the end of every page — and the footer
 * is a place to stop, not a destination. Headings are `--text-secondary`, links are
 * `--text-tertiary` and lift to white on hover, which is 4.9:1 at rest and is why they are
 * set at 16px or above (§9.1).
 *
 * <h2>The language is offered here now, and the currency is still only stated</h2>
 *
 * WS-02 lists both, and until #458 this footer offered neither. The refusal was argued and
 * the argument was right at the time: choosing a language meant reading a cookie, reading a
 * cookie makes a render dynamic, and this component is on `/`, the category landings and
 * every static page — so a control here would have turned all of them into a render per
 * visitor to translate a navigation bar.
 *
 * #123 REMOVED THE PREMISE RATHER THAN THE COST. The language is a path segment now, so
 * switching is a link from one cached address to another and nothing is read at render time.
 * `src/i18n/request.ts` states the consequence: "there is no longer a performance argument
 * for leaving any surface in English." `LanguageSwitch` is the one client boundary in this
 * file, and it is a boundary only because the anchors need the route's own path; the footer
 * around it is unchanged and still renders on the server.
 *
 * <p>The old sentence this replaced — "this build serves the public site in English" — was
 * the honest statement of a limitation that no longer exists.
 *
 * THE CURRENCY IS A CONTROL ELSEWHERE, AND IT IS STILL NOT HERE. #327 built the rate source
 * §21.2 asks for — the Central Bank of Azerbaijan's daily publication, refreshed hourly — so
 * `/settings/language`'s currency panel is a real choice rather than the sentence #280 could
 * honestly offer. The language's argument does not carry over to it: a display currency is a
 * per-reader preference with nothing in the URL to carry it, so a control here would have to
 * know who is reading, which is the dynamic render #458 was careful not to reintroduce.
 *
 * What it states is what every visitor is charged in, which does not vary by reader: §21.2
 * collects in the campaign's currency, and phase 1's campaigns are all in manat. A display
 * currency is an approximation laid over that, and it belongs where somebody has already
 * said who they are.
 *
 * <h2>There is no Legal column</h2>
 *
 * §8.6's sketch draws one and it is deliberately absent. §22 owns that copy, #293 is
 * `status: needs-decision`, and a Terms link resolving to a 404 is a promise about a
 * document that does not exist. `navigation.ts` records the same decision beside the data.
 */

export async function SiteFooter() {
  /*
   * The footer's words, and the language the page was drawn in. That value used to pick one
   * name out of `LOCALE_NAMES` to print as a statement; since #458 it marks which of the four
   * the reader is on, and the other three are the way out of it. Each is still named in
   * itself — a reader looking for их язык recognises "Русский" and not "Russian".
   */
  const [copy, locale] = await Promise.all([footerCopy(), getLocale()]);

  /*
   * `getLocale()` is typed as `string`, so it is narrowed rather than asserted. The value can
   * only be one of the four — the layout calls `notFound()` on anything else before this
   * renders — and narrowing costs nothing while a cast would be a lie the compiler stops
   * checking.
   */
  const language = localeOrDefault(locale);

  return (
    <footer className="mt-24 border-t border-white/6 bg-surface-1">
      <div className="mx-auto w-full max-w-[1400px] px-5 py-14 sm:px-6">
        <div className="flex flex-col gap-12 lg:flex-row lg:justify-between">
          {/*
            The platform's own statement of what it is (WS-02). It says the funding model,
            because all-or-nothing is the single fact a first-time visitor most needs and
            most often assumes wrongly — §5.1 is the rule it describes.
          */}
          <div className="max-w-[38ch]">
            <p className="text-lg font-medium tracking-[-0.02em] text-white">IdeaNest</p>
            <p className="mt-3 text-[15px] leading-relaxed text-white/64">{copy.tagline}</p>
          </div>

          <nav aria-label={copy.label} className="grid grid-cols-2 gap-x-10 gap-y-10 sm:grid-cols-3">
            {copy.groups.map((group) => (
              <div key={group.heading}>
                <h2 className="text-sm font-medium tracking-[-0.01em] text-white/64">
                  {group.heading}
                </h2>
                <ul className="mt-4 flex list-none flex-col gap-3">
                  {group.links.map((link) => (
                    <li key={link.href}>
                      <Link
                        href={link.href}
                        className="text-base text-white/64 transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                      >
                        {link.label}
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </nav>
        </div>

        <div className="mt-14 flex flex-col gap-4 border-t border-white/6 pt-8 text-sm text-white/40 sm:flex-row sm:items-center sm:justify-between">
          {/*
            No year. A copyright line built from `new Date()` is a value that differs between
            the server render and the browser, which React reports as a hydration mismatch —
            and it is also the kind of date that quietly goes stale on a statically rendered
            page. The claim is true without one.
          */}
          <p>© IdeaNest</p>

          <div className="flex flex-col gap-4 sm:flex-row sm:flex-wrap sm:items-center sm:gap-x-6 sm:gap-y-2">
            {/*
              The language is a control and the currency is a statement, so they are no longer
              two rows of one description list. A `<dd>` holding four links would be a
              definition of a term that is really a choice, and `LanguageSwitch` names itself
              as a navigation landmark — which is what a reader looking for the control will
              be moving between.
            */}
            <LanguageSwitch heading={copy.languageHeading} current={language} />

            <dl className="flex items-center gap-2">
              <dt>{copy.currencyHeading}</dt>
              <dd className="text-white/64">{copy.currencyValue}</dd>
            </dl>
          </div>
        </div>
      </div>
    </footer>
  );
}
