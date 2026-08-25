import { describe, expect, it } from 'vitest';
import az from '../../../messages/az.json';
import en from '../../../messages/en.json';
import ru from '../../../messages/ru.json';
import tr from '../../../messages/tr.json';
import { SUPPORTED_LOCALES, type Locale } from './locale';

/**
 * Properties every catalogue must have, whatever is in it — issue #324.
 *
 * These are not about any one screen. They are the defects that survive review because the
 * reviewer does not read all four languages, and that a per-component test would only catch
 * for the component it covers.
 */
const CATALOGUES: Record<Locale, unknown> = { az, en, ru, tr };

/** The scripts each language is actually written in. */
const CYRILLIC = /[Ѐ-ӿ]/u;
const LATIN_SCRIPT: readonly Locale[] = ['az', 'en', 'tr'];

function entries(value: unknown, path = ''): Array<[string, string]> {
  if (typeof value === 'string') return [[path, value]];
  if (typeof value !== 'object' || value === null) return [];

  return Object.entries(value).flatMap(([key, child]) =>
    entries(child, path === '' ? key : `${path}.${key}`),
  );
}

const KEYS_OF = (locale: Locale) => entries(CATALOGUES[locale]).map(([key]) => key);

describe('the message catalogues', () => {
  it('hold exactly the same keys, so no language can ship a screen half-translated', () => {
    /*
     * A missing key does not throw in production — `getMessageFallback` renders the key's own
     * name — so the failure is a Turkish reader shown `settings.pages.security.intro` where a
     * sentence belongs, on a page nobody on the team reads in Turkish.
     */
    const english = [...KEYS_OF('en')].sort();

    for (const locale of SUPPORTED_LOCALES) {
      expect([...KEYS_OF(locale)].sort(), `${locale} against en`).toEqual(english);
    }
  });

  it.each(SUPPORTED_LOCALES)('has no empty or whitespace-only message in %s', (locale) => {
    for (const [key, message] of entries(CATALOGUES[locale])) {
      expect(message.trim(), `${locale} ${key}`).not.toBe('');
    }
  });

  it.each(LATIN_SCRIPT)('writes %s in the Latin script, with no Cyrillic homoglyphs', (locale) => {
    /*
     * THE DEFECT THIS EXISTS FOR IS INVISIBLE. Cyrillic а, е, о, р, с, х and у are drawn
     * identically to their Latin counterparts in almost every typeface, so a single one that
     * slips into an Azerbaijani or Turkish string — pasted from a Russian draft, or typed on a
     * keyboard left in the wrong layout — reads as correct to every human reviewer.
     *
     * It is not harmless. The word stops matching a search, a screen reader switches voice
     * mid-word, and until the `cyrillic` cut was added to Inter it also rendered in a
     * different typeface than the letters beside it. One was found in
     * `account.pages.surveys.intro` — "buraxılış" written with a Cyrillic х — by the sweep
     * this test is the permanent form of.
     */
    for (const [key, message] of entries(CATALOGUES[locale])) {
      expect(CYRILLIC.test(message), `${locale} ${key}: ${message}`).toBe(false);
    }
  });

  it('keeps every rich-text tag balanced and matched across languages', () => {
    /*
     * `t.rich` throws when a string uses a tag the call site does not supply, and renders a
     * sentence with a link silently missing when a translation drops one. Comparing each
     * language's tags against English catches both from the catalogue side, for every
     * namespace at once, rather than one screen at a time.
     */
    const tagsIn = (message: string) =>
      [...message.matchAll(/<(\w+)>/gu)].map((match) => match[1] as string).sort();

    for (const [key, english] of entries(CATALOGUES['en'])) {
      const expected = tagsIn(english);
      if (expected.length === 0) continue;

      for (const locale of SUPPORTED_LOCALES) {
        const message = entries(CATALOGUES[locale]).find(([other]) => other === key)?.[1] ?? '';

        expect(tagsIn(message), `${locale} ${key}`).toEqual(expected);

        for (const tag of new Set(expected)) {
          expect(message, `${locale} ${key} closes <${tag}>`).toContain(`</${tag}>`);
        }
      }
    }
  });

  it('uses one dash convention, so a sentence does not change shape between languages', () => {
    /*
     * The English copy uses an em dash with spaces around it, which is the house style visible
     * throughout `docs/`. A translation that used a hyphen instead is not wrong enough to
     * report and is exactly the kind of drift that accumulates until the interface reads as
     * having been written by four people, which it was.
     */
    for (const locale of SUPPORTED_LOCALES) {
      for (const [key, message] of entries(CATALOGUES[locale])) {
        expect(message, `${locale} ${key} uses a spaced hyphen where an em dash belongs`).not.toMatch(
          / - /u,
        );
      }
    }
  });

  it('never uses a word that is right in one sense and wrong in this one', () => {
    /*
     * A short list of confusions that read as fluent and mean something else. Each earned its
     * place by being written, shipped past a first reading, and caught later.
     *
     * `təhsil` is Azerbaijani for *education*. It is a near-homograph of the Turkish
     * `tahsil`, which does mean collecting a payment, and the borrowing is a natural mistake
     * for anybody drafting the two languages side by side. Twice in this catalogue's history a
     * pledge was described as being "educated" when a campaign closed — a sentence that
     * parses, sounds official, and tells a backer nothing about their money.
     *
     * This is not a spell-checker and is not trying to be. It is a note-to-self with teeth,
     * for the specific errors that have actually happened here.
     */
    const CONFUSIONS: ReadonlyArray<readonly [Locale, RegExp, string]> = [
      ['az', /təhsil/iu, 'means education — for money use tutulur, çıxılır or alınır'],
    ];

    for (const [locale, pattern, why] of CONFUSIONS) {
      for (const [key, message] of entries(CATALOGUES[locale])) {
        expect(pattern.test(message), `${locale} ${key}: ${why}`).toBe(false);
      }
    }
  });
});
