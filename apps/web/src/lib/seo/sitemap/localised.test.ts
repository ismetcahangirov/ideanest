import { describe, expect, it } from 'vitest';
import { languageAlternates, localePath, localisedEntries } from './localised';

const BASE = 'https://ideanest.az';

describe('localePath', () => {
  it('puts the language in front of a path', () => {
    expect(localePath('/discover', 'az')).toBe('/az/discover');
    expect(localePath('/projects/aysel/kilims', 'ru')).toBe('/ru/projects/aysel/kilims');
  });

  it('does not leave a trailing slash on the home page', () => {
    /* `/az/` and `/az` would be two addresses for one document, and the sitemap would list
     * whichever this function returned while the canonical named the other. */
    expect(localePath('/', 'az')).toBe('/az');
    expect(localePath('/', 'en')).toBe('/en');
  });
});

describe('languageAlternates', () => {
  it('names every language including the one being rendered', () => {
    /*
     * Self-reference is not redundancy — the specification asks for it, and Google treats an
     * hreflang cluster whose members do not all name each other as unconfirmed and discards
     * it rather than applying half of it.
     */
    expect(languageAlternates('/discover', BASE)).toEqual({
      az: `${BASE}/az/discover`,
      en: `${BASE}/en/discover`,
      ru: `${BASE}/ru/discover`,
      tr: `${BASE}/tr/discover`,
      'x-default': `${BASE}/en/discover`,
    });
  });

  it('points x-default at English, which is what an unmatched reader is served', () => {
    /*
     * `x-default` is not "the main language" — it is the page for a reader whose language is
     * none of the four, which is exactly what `proxy.ts` does with no cookie set. The two
     * have to agree or the annotation describes a redirect that does not happen.
     */
    const alternates = languageAlternates('/', BASE);

    expect(alternates['x-default']).toBe(`${BASE}/en`);
    expect(alternates['x-default']).toBe(alternates['en']);
  });
});

describe('localisedEntries', () => {
  it('turns one path into one entry per language', () => {
    expect(localisedEntries('/about', BASE).map((entry) => entry.url)).toEqual([
      `${BASE}/az/about`,
      `${BASE}/en/about`,
      `${BASE}/ru/about`,
      `${BASE}/tr/about`,
    ]);
  });

  it('copies the page facts onto every language unchanged', () => {
    /* A campaign whose deadline passed yesterday passed it in all four languages. */
    const lastModified = new Date('2026-05-01T09:00:00.000Z');
    const entries = localisedEntries('/projects/aysel/kilims', BASE, {
      lastModified,
      changeFrequency: 'daily',
    });

    for (const entry of entries) {
      expect(entry.lastModified).toEqual(lastModified);
      expect(entry.changeFrequency).toBe('daily');
    }
  });

  it('gives all four the identical alternates map', () => {
    const entries = localisedEntries('/how-it-works', BASE);
    const expected = languageAlternates('/how-it-works', BASE);

    for (const entry of entries) expect(entry.alternates?.languages).toEqual(expected);
  });

  it('states no priority, because there is no honest number to state', () => {
    for (const entry of localisedEntries('/about', BASE)) expect(entry.priority).toBeUndefined();
  });
});
