import Constants from 'expo-constants';
import { getLocales } from 'expo-localization';

/**
 * What the application was built pointing at, read back at run time.
 *
 * `app.config.ts` resolves the two origins from the environment at build time
 * and puts them in `extra`; this is the only module that reads them out again,
 * so a screen that needs the site URL asks here rather than reaching into
 * `Constants` and getting `undefined` on the one build where the variable was
 * missing.
 */

interface Extra {
  readonly apiOrigin?: string;
  readonly siteUrl?: string;
}

function extra(): Extra {
  return (Constants.expoConfig?.extra ?? {}) as Extra;
}

/**
 * Where the service is.
 *
 * Throws rather than defaulting. A mobile build with no API origin cannot do
 * anything at all, and the failure is far more legible here — at the first
 * request, naming the missing key — than as a stream of `fetch` errors against
 * `undefined/v1/discover`.
 */
export function apiOrigin(): string {
  const origin = extra().apiOrigin;
  if (origin === undefined || origin === '') {
    throw new Error('This build has no apiOrigin. Set IDEANEST_API_ORIGIN and rebuild.');
  }
  return origin;
}

/** The public origin whose links this application claims — used by #114. */
export function siteUrl(): string {
  const url = extra().siteUrl;
  if (url === undefined || url === '') {
    throw new Error('This build has no siteUrl. Set IDEANEST_SITE_URL and rebuild.');
  }
  return url;
}

/**
 * The languages §21.1 ships, and the one this device asks for.
 *
 * The service negotiates on `Accept-Language` across six controllers and sets
 * `Vary: Accept-Language` on all of them, so what the client sends decides the
 * language of every category name, collection title and facet label. `apps/web`
 * closed this gap in #324 by sending the language it renders in; the phone's
 * answer is the phone's own language, narrowed to what the platform actually
 * has copy for — an unrecognised tag would otherwise ask for a language the
 * service falls back out of on every request.
 */
export const SUPPORTED_LOCALES = ['az', 'en', 'ru', 'tr'] as const;

export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number];

export const DEFAULT_LOCALE: SupportedLocale = 'en';

export function isSupportedLocale(value: string): value is SupportedLocale {
  return (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

/**
 * The device's preferred language, if the platform has it.
 *
 * The first match wins rather than the first entry: somebody whose phone is set
 * to Georgian with Russian second should read Russian here rather than English,
 * and only a device that prefers none of the four falls back.
 */
export function deviceLocale(): SupportedLocale {
  for (const locale of getLocales()) {
    const tag = locale.languageCode?.toLowerCase();
    if (tag !== undefined && tag !== null && isSupportedLocale(tag)) {
      return tag;
    }
  }
  return DEFAULT_LOCALE;
}
