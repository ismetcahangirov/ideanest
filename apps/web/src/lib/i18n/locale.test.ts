import { describe, expect, it } from 'vitest';
import {
  DEFAULT_LOCALE,
  LOCALE_NAMES,
  LOCALE_OG,
  SUPPORTED_LOCALES,
  isLocale,
  localeOrDefault,
} from './locale';

describe('the supported languages', () => {
  /*
   * SPELLED OUT RATHER THAN DERIVED. This is the third copy of §21.1's list — the other two
   * are `Taxonomy.SUPPORTED_LOCALES` and the `users_locale_supported` check constraint in
   * `V2__create_identity_schema.sql` — and the point of asserting it literally is that
   * adding a language to this file alone fails here and sends whoever did it to find the
   * other two. A test that derived the list from the constant would pass for any list.
   */
  it('are §21.1\'s four, in the order the document states them', () => {
    expect(SUPPORTED_LOCALES).toEqual(['az', 'en', 'ru', 'tr']);
  });

  it('names every one of them in its own language, so a reader who is lost can find theirs', () => {
    for (const locale of SUPPORTED_LOCALES) {
      expect(LOCALE_NAMES[locale]).not.toBe('');
    }

    expect(LOCALE_NAMES.az).toBe('Azərbaycan dili');
    expect(LOCALE_NAMES.ru).toBe('Русский');
  });

  it('pairs each with an Open Graph locale, so `og:locale` cannot name a language `lang` does not', () => {
    for (const locale of SUPPORTED_LOCALES) {
      expect(LOCALE_OG[locale]).toMatch(/^[a-z]{2}_[A-Z]{2}$/u);
      expect(LOCALE_OG[locale].slice(0, 2)).toBe(locale);
    }
  });

  /*
   * The default is a product decision with a reason written above it, so it is asserted
   * rather than left to whichever value happens to be first in the list. See `locale.ts`:
   * the catalogue covers the account area, and defaulting an unasked visitor into
   * Azerbaijani would wrap an Azerbaijani navigation around English page bodies.
   */
  it('falls back to English until the catalogue covers more than the account area', () => {
    expect(DEFAULT_LOCALE).toBe('en');
    expect(SUPPORTED_LOCALES).toContain(DEFAULT_LOCALE);
  });
});

describe('isLocale', () => {
  it('accepts each supported tag', () => {
    for (const locale of SUPPORTED_LOCALES) {
      expect(isLocale(locale)).toBe(true);
    }
  });

  it('refuses a language this platform does not have', () => {
    expect(isLocale('de')).toBe(false);
    expect(isLocale('fr')).toBe(false);
  });

  /*
   * `az-Latn-AZ` is what a browser sends and what the service folds to `az` on arrival. It
   * is NOT storable — the check constraint takes the primary subtag alone — so the guard
   * has to refuse it here rather than let it reach a PATCH that would 400.
   */
  it('refuses a region-tagged variant, which is not what the column stores', () => {
    expect(isLocale('az-Latn-AZ')).toBe(false);
    expect(isLocale('en-GB')).toBe(false);
  });

  it('refuses absence, emptiness and casing', () => {
    expect(isLocale(null)).toBe(false);
    expect(isLocale(undefined)).toBe(false);
    expect(isLocale('')).toBe(false);
    expect(isLocale('  ')).toBe(false);
    expect(isLocale('EN')).toBe(false);
  });
});

describe('localeOrDefault', () => {
  it('returns a supported language unchanged', () => {
    expect(localeOrDefault('ru')).toBe('ru');
    expect(localeOrDefault('az')).toBe('az');
  });

  /*
   * Every caller reads a language out of something a user can edit — a cookie, a form post,
   * a field on an API response. None of them wants a throw: a language that cannot be
   * honoured is a page in English, not a 500.
   */
  it('falls back rather than throwing on anything else', () => {
    expect(localeOrDefault('de')).toBe(DEFAULT_LOCALE);
    expect(localeOrDefault('')).toBe(DEFAULT_LOCALE);
    expect(localeOrDefault(null)).toBe(DEFAULT_LOCALE);
    expect(localeOrDefault(undefined)).toBe(DEFAULT_LOCALE);
  });
});
