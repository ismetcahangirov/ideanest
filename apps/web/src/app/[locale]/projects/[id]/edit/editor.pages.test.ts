import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import az from '../../../../../../messages/az.json';
import en from '../../../../../../messages/en.json';
import ru from '../../../../../../messages/ru.json';
import tr from '../../../../../../messages/tr.json';
import { EDITOR_TABS } from '../../../../../components/campaign-editor/tabs';
import { SUPPORTED_LOCALES, type Locale } from '../../../../../lib/i18n/locale';

/**
 * The six tabs a creator writes their campaign in — issue #459.
 *
 * <h2>What this covers that the catalogue test does not</h2>
 *
 * `lib/i18n/catalogue.test.ts` asserts properties of the messages and cannot see whether a
 * *page* asks for any of them: a route rewritten with a literal back in it passes every
 * catalogue check while showing English to everybody. `auth.pages.test.ts` makes the same
 * argument for the public screens and `account-area.pages.test.ts` for the signed-in ones;
 * this is the editor's counterpart.
 *
 * <h2>Why these routes deserve their own guard</h2>
 *
 * This was the largest untranslated surface on the platform — 190 strings across sixteen
 * components, more than every other area combined — and it is the one a creator spends hours
 * in. A key that was never added renders as `editor.basics.goal.hint` under the figure the
 * whole campaign is measured against.
 *
 * <p>It reads the pages' own source rather than rendering them. Each is a server component
 * that awaits a copy accessor and hands the object to a client panel, and a test that mounted
 * one would be a test of the mock behind `next-intl/server`.
 */
const CATALOGUES: Record<Locale, typeof en> = { az, en, ru, tr };

/** The route segment, and the accessor its page is expected to call. */
const SCREENS: ReadonlyArray<readonly [segment: string, accessor: string]> = [
  ['basics', 'editorBasicsCopy'],
  ['rewards', 'editorRewardsCopy'],
  ['story', 'editorStoryCopy'],
  ['faq', 'editorFaqCopy'],
  ['prelaunch', 'editorPrelaunchCopy'],
  ['review', 'editorReviewCopy'],
];

function sourceOf(segment: string): string {
  return readFileSync(
    join(process.cwd(), 'src/app/[locale]/projects/[id]/edit', segment, 'page.tsx'),
    'utf8',
  );
}

function editorMessages(locale: Locale): Record<string, unknown> {
  return (CATALOGUES[locale] as unknown as Record<string, Record<string, unknown>>)['editor'] ?? {};
}

describe('the campaign editor’s six tabs', () => {
  it('covers every tab the navigation declares, so a seventh cannot be added unguarded', () => {
    /*
     * Derived from `EDITOR_TABS` rather than written out beside it. A section added to that
     * list without a row here would be a page nobody checked, and the failure would be a
     * creator meeting one English tab among six.
     */
    expect(SCREENS.map(([segment]) => segment)).toEqual(EDITOR_TABS.map((tab) => tab.segment));
  });

  it.each(SCREENS)('/%s is drawn from the catalogue', (segment, accessor) => {
    const source = sourceOf(segment);

    /*
     * The tab title is the one piece a reader sees before the page paints, and it followed the
     * build rather than the reader until #459. A `const metadata` cannot read a request, so the
     * assertion is that the route exports the async form.
     */
    expect(source).toContain("getTranslations('editor')");
    expect(source).toContain(`t('frame.tabs.${segment}')`);
    expect(source).toContain(`t('pages.${segment}.metaDescription')`);
    expect(source).not.toContain('export const metadata');

    /* And that the panel below it is handed words rather than left to carry its own. */
    expect(source).toContain(`${accessor}()`);
    expect(source).toContain('copy=');
  });

  it.each(SUPPORTED_LOCALES)('has a description for every tab in %s', (locale) => {
    const pages = editorMessages(locale)['pages'] as Record<string, Record<string, unknown>>;

    for (const [segment] of SCREENS) {
      expect(pages[segment]?.['metaDescription'], `${locale} editor.pages.${segment}`).toBeTypeOf(
        'string',
      );
    }
  });

  it('names every section once, in the frame rather than in six places', () => {
    /*
     * `tabs.ts` held a `label` on each row until #459 and the catalogue holds the words now.
     * The tab strip, the browser's tab title and the review tab's "Fix in Basics" link all read
     * `editor.frame.tabs.*`, so a section cannot be called one thing in one of them and
     * something else in another.
     */
    for (const locale of SUPPORTED_LOCALES) {
      const frame = editorMessages(locale)['frame'] as Record<string, Record<string, unknown>>;

      for (const tab of EDITOR_TABS) {
        expect(frame['tabs']?.[tab.key], `${locale} editor.frame.tabs.${tab.key}`).toBeTypeOf(
          'string',
        );
      }
    }
  });

  it('names all sixteen §6.1 states for the creator, in every language', () => {
    /*
     * The editor's own vocabulary, deliberately separate from the console's — a moderator sees
     * a queue ("Awaiting review"), a creator sees work that has left their hands ("In review").
     * Separate does not mean incomplete: a state with no word renders its own key on the badge
     * beside the campaign's title.
     */
    const english = Object.keys(
      (editorMessages('en')['frame'] as Record<string, Record<string, unknown>>)['states'] ?? {},
    );
    expect(english).toHaveLength(16);

    for (const locale of SUPPORTED_LOCALES) {
      const frame = editorMessages(locale)['frame'] as Record<string, Record<string, unknown>>;

      for (const state of english) {
        expect(frame['states']?.[state], `${locale} editor.frame.states.${state}`).toBeTypeOf(
          'string',
        );
      }
    }
  });

  it('leaves no English literal in the panels the six pages render', () => {
    /*
     * THE REGRESSION THIS EXISTS FOR IS A SENTENCE TYPED BACK IN. Every word in this directory
     * arrives as a prop now, and the next person to add a field will reach for a string literal
     * because that is what a form component usually holds. Nothing else in the repository would
     * notice: the catalogue stays complete, every other test passes, and a creator reading
     * Azerbaijani meets one English hint.
     *
     * <p>The scan is for a `label=`, `hint=`, `title=`, `description=` or `placeholder=` whose
     * value is a quoted literal. Those five are the props the kit's form primitives draw
     * verbatim, which is where a retyped sentence would land. An `aria-label` is deliberately
     * not in the list — several are built from a name the server supplied — and neither is
     * `label` on `IconButton`, which this directory always builds from a template.
     */
    const directory = join(process.cwd(), 'src/components/campaign-editor');
    const offenders: string[] = [];

    for (const file of FILES) {
      const source = readFileSync(join(directory, file), 'utf8');

      for (const [whole] of source.matchAll(
        /\s(?:label|hint|title|description|placeholder|dragPrompt|prompt|buttonLabel)="[^"]+"/gu,
      )) {
        const found = whole.trim();
        if (!EXEMPT.includes(found)) offenders.push(`${file}:${found}`);
      }
    }

    expect(
      offenders,
      'A literal where a catalogue key belongs. Add it to messages/*.json and reach it through ' +
        'the tab’s copy object — lib/i18n/editor-copy.ts.',
    ).toEqual([]);
  });
});

/**
 * The two literals that are not words, pinned by their exact text.
 *
 * `https://` is a URL scheme and `AZ` is an ISO 3166 country code — the field beside it accepts
 * exactly two letters and refuses anything else. Neither is written differently in Azerbaijani,
 * Russian or Turkish, and putting them in the catalogue would only offer somebody the chance to
 * translate a token the service parses.
 *
 * <p>Listed rather than matched by a cleverness that would exempt the next one too. A third
 * entry here is something somebody looks at.
 */
const EXEMPT: readonly string[] = ['placeholder="https://"', 'placeholder="AZ"'];

/**
 * The components the six pages render, listed rather than walked.
 *
 * A directory walk would silently cover nothing if the path were ever wrong, and this list is
 * short enough to keep honest — `EDITOR_TABS` above is what stops the tabs themselves drifting.
 */
const FILES: readonly string[] = [
  'BasicsPanel.tsx',
  'CoverImageField.tsx',
  'EditorDrawer.tsx',
  'EditorShell.tsx',
  'FaqEntryEditor.tsx',
  'FaqPanel.tsx',
  'ItemEditor.tsx',
  'ItemsSection.tsx',
  'PrelaunchPanel.tsx',
  'ReviewPanel.tsx',
  'RewardsPanel.tsx',
  'RewardTierEditor.tsx',
  'SaveStatus.tsx',
  'StoryBlockEditor.tsx',
  'StoryMarkToolbar.tsx',
  'StoryPanel.tsx',
  'StoryTextField.tsx',
  'StoryVersionHistory.tsx',
];
