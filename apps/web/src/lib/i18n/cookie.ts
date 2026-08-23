import {
  LOCALE_COOKIE,
  LOCALE_COOKIE_MAX_AGE_SECONDS,
  type Locale,
  isLocale,
} from './locale';

/**
 * Reading and writing the language cookie from the browser — issue #324.
 *
 * <h2>Why the parsing is a separate, pure function</h2>
 *
 * {@link readLocaleCookie} takes the cookie string rather than reaching for
 * `document.cookie` itself, so the parsing is testable without a DOM and callable from
 * anywhere that happens to hold a `Cookie` header. {@link currentLocaleCookie} is the thin
 * wrapper that knows about `document`, and it is the only part of this file that cannot
 * run on the server.
 *
 * <h2>There is no cookie library here on purpose</h2>
 *
 * One cookie, one name, a value constrained to four known tags. A parser for that is the
 * six lines below; a dependency for it is a supply-chain surface and a bundle cost on
 * every route for a `split(';')`.
 */

/**
 * The language remembered in a cookie string, or `null`.
 *
 * `null` rather than the default, because the two are different facts and the caller
 * decides what to do about it: "no preference has ever been stated" is what makes the
 * account's own `users.locale` authoritative, and collapsing it into `en` here would make
 * a signed-in Russian speaker's stored preference unreachable on their first request from
 * a new device.
 *
 * A value that is present but not a supported language is treated as absent. Cookies are
 * user-editable and outlive deployments, so `ideanest_locale=de` is what a removed
 * language leaves behind, and it means exactly as much as no cookie at all.
 */
export function readLocaleCookie(cookieString: string): Locale | null {
  for (const pair of cookieString.split(';')) {
    const separator = pair.indexOf('=');
    if (separator === -1) continue;

    if (pair.slice(0, separator).trim() !== LOCALE_COOKIE) continue;

    const value = decodeURIComponent(pair.slice(separator + 1).trim());
    return isLocale(value) ? value : null;
  }

  return null;
}

/**
 * The language this browser has remembered, or `null`.
 *
 * Returns `null` rather than throwing when there is no `document`, so that a module
 * imported by both halves of the application does not have to guard every call site. A
 * server render has its own way to read the cookie (`src/i18n/request.ts`, through
 * `next/headers`) and never reaches this.
 */
export function currentLocaleCookie(): Locale | null {
  if (typeof document === 'undefined') return null;
  return readLocaleCookie(document.cookie);
}

/**
 * Remembers a language in this browser.
 *
 * `SameSite=Lax` rather than `Strict`: a person following a link to this platform from
 * anywhere else should arrive in the language they chose, and `Strict` would withhold the
 * cookie on exactly that first cross-site navigation — the one that renders the page they
 * see first.
 *
 * `Path=/` because the preference applies to the whole application, and no `Secure`
 * attribute is set here because local development is served over HTTP and a cookie the
 * development server cannot set is a preference that silently does nothing. The value is
 * one of four known tags rather than anything sensitive, so the transport is not carrying
 * a secret either way.
 */
export function writeLocaleCookie(locale: Locale): void {
  document.cookie =
    `${LOCALE_COOKIE}=${locale}; Path=/; Max-Age=${LOCALE_COOKIE_MAX_AGE_SECONDS}; SameSite=Lax`;
}
