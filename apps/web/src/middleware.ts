import createIntlMiddleware from 'next-intl/middleware';
import { type NextRequest, NextResponse } from 'next/server';
import { LOCALE_COOKIE, isLocale, localeOrDefault } from './lib/i18n/locale';
import { routing } from './i18n/routing';

/**
 * Where a request with no language in its path is sent — issue #123.
 *
 * <h2>Why this is not just `createIntlMiddleware`</h2>
 *
 * next-intl's middleware can pick a language on its own, and `routing.ts` turns that off.
 * Its detection reads `Accept-Language`, which makes the response vary by that header, and
 * a `Vary: Accept-Language` on the front door fragments the shared CDN cache per browser
 * configuration. The whole reason the language moved into the path was to stop paying a
 * per-visitor cost on cached routes; negotiating on a header would put it back.
 *
 * So this file answers the one question detection existed to answer — *a request arrived
 * at `/discover` with no language, which language did this person mean?* — from the stored
 * preference alone, and delegates everything else.
 *
 * <h2>The cookie's job changed, and shrank</h2>
 *
 * It used to be what a render read to know what language to draw. It is not that any more:
 * the path says the language, and a render that needed the cookie would be dynamic again.
 * All the cookie does now is answer the redirect above — *last time, this person chose
 * Russian* — so a returning reader who types the bare domain is not met by English.
 *
 * `users.locale` remains the durable record and this remains its cache. `SessionProvider`
 * still mirrors the account's column into it on bootstrap, so the preference survives a new
 * device; that mechanism is untouched.
 *
 * <h2>307 AND NOT 308, DELIBERATELY</h2>
 *
 * The destination depends on a cookie, so it is not permanent and must never be recorded as
 * such. A browser that cached a 308 from `/` to `/en` would keep sending itself to English
 * after the reader chose Azerbaijani — from its own cache, without asking, in a way that
 * clearing the site's cookies does not fix and that no server-side change can reach.
 */
const intlMiddleware = createIntlMiddleware(routing);

export default function middleware(request: NextRequest): NextResponse {
  const { pathname } = request.nextUrl;

  /*
   * `/az/discover` splits to `['', 'az', 'discover']`, so the candidate is always index 1.
   * A path that already names a language is next-intl's to handle.
   */
  if (isLocale(pathname.split('/')[1])) return intlMiddleware(request);

  const locale = localeOrDefault(request.cookies.get(LOCALE_COOKIE)?.value);
  const destination = request.nextUrl.clone();

  /*
   * `/` must become `/az` rather than `/az/`. Every other path is appended whole, which
   * preserves the query string and hash because `clone()` carries them and only `pathname`
   * is being replaced.
   */
  destination.pathname = pathname === '/' ? `/${locale}` : `/${locale}${pathname}`;

  return NextResponse.redirect(destination, 307);
}

export const config = {
  /*
   * WHAT IS DELIBERATELY NOT MATCHED, EACH FOR ITS OWN REASON.
   *
   * `api/` is the RUM beacon. It is called by `WebVitals` from every page including the
   * localised ones, and a redirect on a `sendBeacon` is a measurement silently lost.
   *
   * `_next/` and anything with a dot in the last segment are build output and static files.
   * Prefixing a hashed chunk with a language would 404 it.
   *
   * `robots.txt`, `sitemap.xml` and `sitemap_index.xml` are addresses fixed by convention
   * rather than by this application, and a crawler that follows a redirect to `/en/robots.txt`
   * has been given a file at an address it will not treat as authoritative. They stay at the
   * root and enumerate the localised pages from there.
   */
  matcher: ['/((?!api|_next|robots\\.txt|sitemap\\.xml|sitemap_index\\.xml|.*\\.[\\w]+$).*)'],
};
