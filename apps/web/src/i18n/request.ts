import { getRequestConfig } from 'next-intl/server';
import { cookies } from 'next/headers';
import { DEFAULT_LOCALE, LOCALE_COOKIE, localeOrDefault } from '../lib/i18n/locale';

/**
 * How a server render learns which language to draw — issue #324, docs/architecture.md §21.1.
 *
 * <h2>Cookie negotiation, and deliberately no `[locale]` URL segment</h2>
 *
 * §21.1 asks that interface text be key-based. It says nothing about URLs, and
 * locale-prefixed paths with `hreflang` alternates are a separate, unstarted decision
 * (#123, `area: seo`). A stored preference does not need them: a cookie names the language
 * before the first byte is written, which is all a server render requires.
 *
 * <h2>WHY ONLY THE ACCOUNT AREA IS TRANSLATED TODAY, AND WHY THAT IS A PERFORMANCE FACT
 * RATHER THAN AN UNFINISHED CHORE</h2>
 *
 * Reading a cookie makes a route dynamic. That is free on `/settings/*` and `/account/*`,
 * which are behind authentication and render per person already, and it is expensive on
 * the public site: `/`, the category landings and the static pages are cached reads today,
 * and translating their shell through this file would turn every one of them into a
 * per-request render for a navigation bar. The cost would be paid on the largest
 * contentful paint of the pages a stranger meets first.
 *
 * **This is the point at which #123 stops being unrelated.** A locale-prefixed URL is how
 * a translated public page stays statically rendered — one cached render per language,
 * keyed by the path — so the SEO issue and the public half of the catalogue are the same
 * piece of work, and this file is not it. `apps/web/README.md` records which routes are
 * key-based and which are still English literals.
 *
 * <h2>The signed-in account's own column</h2>
 *
 * `users.locale` is the durable record and this cookie is its cache. The preference screen
 * writes both, and `SessionProvider` mirrors the account's value into the cookie when a
 * session bootstraps, so a person who chose Russian on one device is not met by English on
 * the next. The cookie is what a render reads because a render must not wait on an API
 * call to know what language to draw in.
 */
export default getRequestConfig(async () => {
  /*
   * `cookies()` is async in Next 16 and is what marks this render dynamic. It is only ever
   * reached from a route that already was — see the note above — so it costs nothing that
   * was not already being paid.
   */
  const store = await cookies();
  const locale = localeOrDefault(store.get(LOCALE_COOKIE)?.value);

  return {
    locale,

    /*
     * One catalogue per language, loaded by the tag that was just validated — never by an
     * unchecked cookie value, which would be an attacker-chosen path into a dynamic
     * import. `localeOrDefault` narrows to one of four literals before this line.
     */
    messages: (await import(`../../messages/${locale}.json`)).default,

    /*
     * WHAT A MISSING KEY DOES. In development it throws, so a key that was never added is
     * found by the person adding it. In production it renders the key's own name rather
     * than taking the route down: a navigation item reading `account.nav.saved` is a
     * defect, and a 500 on the settings page because one string was not translated into
     * Turkish is an outage. `onError` is left at its default, which logs.
     */
    getMessageFallback: ({ key, namespace }) => (namespace === undefined ? key : `${namespace}.${key}`),

    /*
     * NO `timeZone` AND NO `formats` HERE, DELIBERATELY. §21.1 asks that dates and numbers
     * use the platform internationalisation APIs, and they already do — `lib/time.ts` and
     * `lib/projects/deadline.ts` own that formatting and both are pinned to English with
     * the limitation written down. Declaring a zone in this file would silently change how
     * every date those modules render behaves the moment one of them started asking
     * next-intl instead, which is a migration with its own tests rather than a default set
     * in passing. Money never goes through here at all: `lib/money.ts` formats against the
     * campaign's currency, which is a property of the project and never of the reader.
     */
  };
});

/**
 * Re-exported so that the one place that decides the fallback and the one place that reads
 * the cookie are visibly the same module to anybody reading this file alone.
 */
export { DEFAULT_LOCALE };
