'use client';

import NextLink from 'next/link';
import {
  useParams,
  usePathname as useNextPathname,
  useRouter as useNextRouter,
} from 'next/navigation';
import { useMemo } from 'react';
import type { ComponentProps } from 'react';
import { type Locale, localeOrDefault } from '../lib/i18n/locale';

/**
 * Locale-aware replacements for `next/link` and `usePathname` — issue #123.
 *
 * <h2>Why these are written here rather than taken from next-intl</h2>
 *
 * next-intl ships exactly this, as `createNavigation`, and it was tried first. Its client
 * hooks resolve the language through `use-intl`'s `useLocale`, which reads a React context
 * and **throws** when there is none — there is no fallback to the route's own parameters, as
 * `react-client/index.js` and `use-intl`'s `useIntlContext` both show. Using it would
 * therefore require a `NextIntlClientProvider` above every client component that renders a
 * link, which in this application is most of them.
 *
 * That provider is the thing this repository has already measured and refused once:
 * `apps/web/README.md` records it adding up to 27.4 KiB to *every* route in a group, paid by
 * routes that render no translated text at all. Trading that for a hook is the wrong way
 * round when the hook is fifteen lines.
 *
 * `useParams()` gives the same answer for free. The language is a path segment, so the router
 * already parsed it before any of this ran; reading it costs no context, no provider, and
 * nothing in the bundle that `next/link` did not already cost.
 *
 * <h2>What still comes from next-intl</h2>
 *
 * Everything on the server: `getTranslations`, `setRequestLocale`, and the catalogue itself.
 * Those have no bundle cost because they never reach the browser. This file is only about the
 * two hooks that do.
 */

/** The route's language, from the segment the router matched. */
export function useLocale(): Locale {
  /*
   * `useParams()` returns `null` outside a Next runtime — in a unit test that renders a
   * component directly, for instance — and a string array if a route ever had a catch-all
   * segment here. `localeOrDefault` answers English to both rather than throwing, which is
   * the same fallback every other boundary in `lib/i18n/locale.ts` takes: a language that
   * cannot be determined is a page in English, never an error page.
   */
  const params = useParams();
  const value = params?.['locale'];
  return localeOrDefault(typeof value === 'string' ? value : undefined);
}

/** `/discover` in, `/az/discover` out. The single place a language is put onto a path. */
export function localeHref(href: string, locale: Locale): string {
  /* Only in-application paths are prefixed. An absolute URL, a `mailto:`, a `#fragment` or a
   * protocol-relative address is somebody else's and must be left exactly as written. */
  if (!href.startsWith('/') || href.startsWith('//')) return href;

  return href === '/' ? `/${locale}` : `/${locale}${href}`;
}

export type LinkProps = ComponentProps<typeof NextLink>;

/**
 * `next/link`, with the current language kept.
 *
 * WITHOUT THIS, EVERY LINK IS A LANGUAGE RESET. `<Link href="/discover">` inside the Russian
 * site points at the un-prefixed path, which `proxy.ts` answers with a redirect decided
 * by a cookie — so a reader three pages into Russian can be moved to Azerbaijani by clicking
 * a navigation item, and it does not reproduce for anyone whose cookie happens to agree.
 *
 * Call sites keep writing `/discover`. `navigation.guard.test.ts` fails the suite if any
 * module outside `src/i18n` imports `next/link` at all, so the next one written cannot
 * quietly go back to the raw component — there is no ESLint in this repository, and a rule
 * enforced only by review is a rule that holds until the first busy week.
 */
export function Link({ href, ...rest }: LinkProps) {
  const locale = useLocale();

  /*
   * `href` may be a `UrlObject`. Those are rare here and are passed through untouched rather
   * than half-handled: prefixing `pathname` while ignoring `query` and `hash` would produce a
   * link that works in review and drops the filter in production.
   */
  return <NextLink href={typeof href === 'string' ? localeHref(href, locale) : href} {...rest} />;
}

/**
 * The pathname a page compares against, with the language taken back off.
 *
 * `usePathname()` returns `/az/settings/notifications`, and every caller in this application
 * compares it against a route written without a language — `lib/account/navigation.ts` and
 * `components/shell/navigation.ts` both hold their paths that way. Without this the
 * comparison never matches, so no navigation item is ever marked `aria-current="page"`: the
 * whole site loses its "you are here", visibly to a sighted reader and structurally to a
 * screen reader, and nothing throws.
 */
export function usePathname(): string {
  const pathname = useNextPathname();
  const locale = useLocale();

  return stripLocale(pathname, locale);
}

/**
 * `/az/settings` → `/settings`, and `/az` → `/`.
 *
 * Exported for the tests and for anything that has a pathname from somewhere other than the
 * hook. It only strips the language it is given: a campaign whose slug happened to be `az`
 * sits at `/en/projects/az`, and a blind `split('/')` would eat it.
 */
export function stripLocale(pathname: string | null, locale: Locale): string {
  if (pathname === null) return '/';
  if (pathname === `/${locale}`) return '/';
  if (pathname.startsWith(`/${locale}/`)) return pathname.slice(locale.length + 1);

  return pathname;
}

/**
 * `useRouter()`, with the current language kept on every path it is given.
 *
 * The same defect as `Link` and harder to see, because a programmatic navigation has no
 * `href` in the markup for anybody to notice: `router.push('/settings')` after saving a form
 * would drop a Russian reader onto the redirect and out of their language, at the moment
 * they are least likely to attribute it to the button they pressed.
 *
 * `back`, `forward` and `refresh` are passed through untouched — they take no path.
 */
export function useRouter() {
  const router = useNextRouter();
  const locale = useLocale();

  /*
   * Memoised on the router and the language rather than rebuilt each render, so that the
   * object is stable enough to sit in a `useEffect` dependency list. An unstable router here
   * would turn a "navigate once when saved" effect into a loop.
   */
  return useMemo(
    () => ({
      ...router,
      /*
       * The remaining arguments are forwarded with a spread rather than named and passed on.
       * Naming them would mean calling `router.push(href, undefined)` where the caller wrote
       * `router.push(href)`, and Next's own options argument is optional — an extra
       * `undefined` changes nothing at runtime and changes the call's arity, which is what a
       * spy sees. Keeping the shape means a caller's own tests keep asserting the call they
       * actually make.
       */
      push: (href: string, ...rest: Parameters<typeof router.push> extends [unknown, ...infer R] ? R : never[]) =>
        router.push(localeHref(href, locale), ...rest),
      replace: (
        href: string,
        ...rest: Parameters<typeof router.replace> extends [unknown, ...infer R] ? R : never[]
      ) => router.replace(localeHref(href, locale), ...rest),
      prefetch: (
        href: string,
        ...rest: Parameters<typeof router.prefetch> extends [unknown, ...infer R] ? R : never[]
      ) => router.prefetch(localeHref(href, locale), ...rest),
    }),
    [router, locale],
  );
}
