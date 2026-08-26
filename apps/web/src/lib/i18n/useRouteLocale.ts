'use client';

import { useParams } from 'next/navigation';
import { localeOrDefault, type Locale } from './locale';

/**
 * The language of the route this component is rendered under — issue #324.
 *
 * <h2>Why not next-intl's own `useLocale`</h2>
 *
 * Because it throws here. `useLocale` from `next-intl` reads `IntlProvider`'s context, and
 * this application has no `NextIntlClientProvider` anywhere: one in a shared layout was
 * measured at up to 27.4 KiB on every route in its group, so copy is resolved on the server
 * and handed down as props instead. That trade is right for copy and leaves one gap — a
 * client component that has to *format* something needs the language itself, not a sentence.
 *
 * <h2>Where the answer comes from</h2>
 *
 * The `[locale]` route parameter, which the router has already matched and which costs
 * nothing to read: `useParams` is part of `next/navigation`, which every one of these
 * components imports already. next-intl's own client navigation reads the locale the same
 * way, for the same reason.
 *
 * <p>It is validated rather than trusted, exactly as `i18n/request.ts` validates it: a
 * `[locale]` segment is a wildcard, so `/xx/pledges` matches the route before it fails
 * anything, and a formatter handed `xx` would throw a `RangeError` inside a render.
 */
export function useRouteLocale(): Locale {
  const params = useParams<{ locale?: string | string[] }>();
  const segment = params?.['locale'];

  return localeOrDefault(Array.isArray(segment) ? segment[0] : segment);
}
