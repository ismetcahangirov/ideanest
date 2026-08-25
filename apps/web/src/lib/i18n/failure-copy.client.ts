import type { Locale } from './locale';
import type { FailureCopy } from './shell-copy';

/**
 * The failure pages' shared words, in every language, small enough to ship to the browser —
 * issues #324 and #123.
 *
 * <h2>Why this file exists at all, and why the duplication is deliberate</h2>
 *
 * Every other surface in this application receives its copy as a prop from a server
 * component. Two cannot: `app/[locale]/error.tsx` and `app/[locale]/(site)/error.tsx`. Next
 * requires an error boundary to be a client component and renders it itself, so there is no
 * server parent to hand them anything.
 *
 * The library's answer is `NextIntlClientProvider` plus `useTranslations`, and it was tried
 * and **measured** rather than assumed. A provider in `[locale]/layout.tsx` carrying only the
 * `shell` namespace moved `/[locale]/about` from 571.3 KiB of First Load JS to 596.0 KiB —
 * **+24.7 KiB on every route on the site**, close to the 27.4 KiB `apps/web/README.md`
 * already records for the same mistake, and it put six authentication routes over budget. It
 * is paid by every page for the benefit of two that almost never render.
 *
 * So these eight strings are carried instead. Four languages of them cost well under a
 * kilobyte, they load with the error boundary rather than with the site, and nothing else in
 * the application imports this module.
 *
 * <h2>What stops it drifting from the catalogue</h2>
 *
 * `failure-copy.client.test.ts` asserts this file against `messages/*.json`, key by key and
 * language by language. Editing the catalogue without editing this file fails the suite, and
 * so does the reverse. The duplication is real; the drift is what would have been the defect,
 * and it is the thing that is prevented rather than the copy.
 *
 * If a third client-only surface ever needs the catalogue, do not extend this file — measure
 * the provider again against whatever the bundle looks like then, and write the number down.
 */
const FAILURE_COPY: Record<Locale, FailureCopy> = {
  az: {
    elsewhere: 'IdeaNest-in digər səhifələri',
    links: { browse: 'Kampaniyalara baxın', categories: 'Kateqoriyalar', search: 'Axtarış' },
  },
  en: {
    elsewhere: 'Elsewhere on IdeaNest',
    links: { browse: 'Browse campaigns', categories: 'Categories', search: 'Search' },
  },
  ru: {
    elsewhere: 'Другие разделы IdeaNest',
    links: { browse: 'Смотреть кампании', categories: 'Категории', search: 'Поиск' },
  },
  tr: {
    elsewhere: "IdeaNest'teki diğer sayfalar",
    links: { browse: 'Kampanyalara göz atın', categories: 'Kategoriler', search: 'Arama' },
  },
};

/** "Skip to content", for the same two boundaries and for the same reason. */
const SKIP_TO_CONTENT: Record<Locale, string> = {
  az: 'Məzmuna keç',
  en: 'Skip to content',
  ru: 'Перейти к содержимому',
  tr: 'İçeriğe geç',
};

export function failureCopyOf(locale: Locale): FailureCopy {
  return FAILURE_COPY[locale];
}

export function skipToContentOf(locale: Locale): string {
  return SKIP_TO_CONTENT[locale];
}
