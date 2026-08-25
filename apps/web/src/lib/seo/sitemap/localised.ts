import type { MetadataRoute } from 'next';
import { DEFAULT_LOCALE, type Locale, SUPPORTED_LOCALES } from '../../i18n/locale';
import { absoluteUrl } from './config';

type SitemapEntry = MetadataRoute.Sitemap[number];

/**
 * One indexable page, in four languages — issue #123.
 *
 * <h2>Why the sitemap grew a language dimension</h2>
 *
 * Before the `[locale]` segment there was one address per page and the sitemap listed it.
 * There are now four, `middleware.ts` answers the un-prefixed one with a 307, and a sitemap
 * that still listed `/discover` would be handing a crawler a redirect for every entry it
 * contains — the one document whose entire job is to name addresses that resolve.
 *
 * So each path becomes four entries. That is not padding: `/az/discover` and `/ru/discover`
 * are genuinely different documents with different text, and a search engine that was shown
 * only one of them would have no way to reach the others.
 */
export function localePath(path: string, locale: Locale): string {
  /* `/` must become `/az`, not `/az/` — a trailing slash is a second address for one page. */
  return path === '/' ? `/${locale}` : `/${locale}${path}`;
}

/**
 * The `hreflang` map for one page: every language it exists in, plus `x-default`.
 *
 * <h2>WHY EVERY ENTRY REPEATS THE WHOLE SET, INCLUDING ITSELF</h2>
 *
 * This looks redundant and is required. Google treats `hreflang` as a claim that has to be
 * confirmed from the other side: if `/az/discover` names `/ru/discover` as its Russian
 * alternate but `/ru/discover` does not name `/az/discover` back, the annotation is
 * unreciprocated and the whole cluster is discarded rather than half-applied. Emitting the
 * complete map on all four — self-reference included, which the specification asks for
 * explicitly — is what makes each one confirm the others.
 *
 * <h2>`x-default` is English, and it is a fallback rather than a preference</h2>
 *
 * It names the page to serve a reader whose language is none of the four, which is what
 * `middleware.ts` does with no cookie set. Pointing it at Azerbaijani would be defensible for
 * this market and would be a different claim: `x-default` is not "the main language", it is
 * "the one for readers we could not match", and the platform answers those in English.
 */
export function languageAlternates(path: string, baseUrl: string): Record<string, string> {
  const languages: Record<string, string> = {};

  for (const locale of SUPPORTED_LOCALES) {
    languages[locale] = absoluteUrl(localePath(path, locale), baseUrl);
  }

  languages['x-default'] = absoluteUrl(localePath(path, DEFAULT_LOCALE), baseUrl);

  return languages;
}

/**
 * A path, expanded to one sitemap entry per language.
 *
 * `rest` carries whatever the caller would have put on the single entry — `changeFrequency`,
 * `lastModified` — and it is copied onto all four unchanged, because those properties are
 * facts about the campaign or the page rather than about the translation. A project whose
 * deadline passed yesterday passed it in every language.
 */
export function localisedEntries(
  path: string,
  baseUrl: string,
  rest: Omit<SitemapEntry, 'url' | 'alternates'> = {},
): MetadataRoute.Sitemap {
  const languages = languageAlternates(path, baseUrl);

  return SUPPORTED_LOCALES.map((locale) => ({
    url: absoluteUrl(localePath(path, locale), baseUrl),
    ...rest,
    alternates: { languages },
  }));
}
