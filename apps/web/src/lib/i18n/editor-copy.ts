import type { ProjectState } from '../projects/api';
import type { EditorTabKey } from '../../components/campaign-editor/tabs';

/**
 * Every word the campaign editor draws — issue #459.
 *
 * <h2>Why the whole of it arrives as a prop</h2>
 *
 * The editor was the largest untranslated surface left on the platform: 190 strings across
 * sixteen components, more than every other area combined. A creator who chose Azerbaijani —
 * the platform's primary language — wrote their campaign in a screen that never spoke it.
 *
 * `src/i18n/request.ts` records why it was left. Before #123 a translated surface meant a
 * cookie read and a dynamic render, so the catalogue covered the already-dynamic account area
 * and stopped. That reason is spent: "there is no longer a performance argument for leaving
 * any surface in English."
 *
 * <p>The pattern is `checkout-copy.ts`'s, for its reason. Every panel under
 * `components/campaign-editor` is a client component and has to be — the form autosaves as it
 * is typed, the drawer holds a reward being written, the story editor owns a caret — so none
 * can call `getTranslations`. `useTranslations` would need a `NextIntlClientProvider`, which
 * this repository measured at up to **27.4 KiB on every route in a group**. Each page under
 * `app/[locale]/projects/[id]/edit` resolves its own tab's copy on the server and hands it
 * down.
 *
 * <h2>One shape per tab, and the frame inside each</h2>
 *
 * Six tabs, six copy objects, so a tab can be translated without touching the other five —
 * which is how #459's sub-tasks are cut. {@link EditorFrameCopy} is the part every tab draws
 * (the section links, the state, the save indicator), and it is nested inside each rather than
 * fetched separately so that a page has exactly one accessor to call.
 *
 * <h2>The shapes are exhaustive rather than an index signature</h2>
 *
 * `checkout-copy.ts` gives the reason and it holds here: a missing key should be a compile
 * error, not `editor.basics.goal.hint` printed under the figure a campaign is measured
 * against.
 */

/**
 * A message lookup rooted at `editor`, narrowed to what these builders need.
 *
 * `raw` is next-intl's escape hatch for a message that is not to be formatted here, and the
 * builders use it for every sentence carrying a placeholder. `t('x')` on such a message is a
 * formatting error — next-intl has no value for the argument, calls `onError` and renders the
 * key's own path — so a template is read raw and `fillPlaceholders` fills it in the component,
 * where the number of characters or days is actually known.
 */
export interface EditorTranslator {
  (key: string): string;
  raw(key: string): unknown;
}

/**
 * The frame every tab renders inside — `EditorShell` and `SaveStatus`.
 *
 * <p>`states` is the sixteen of docs/architecture.md §6.1, in the creator's vocabulary. The
 * console has its own set under `admin.screens.campaignDirectory.state` and the two are
 * deliberately not shared: `SUBMITTED` legitimately differs by audience — a moderator sees a
 * queue ("Awaiting review"), a creator sees work that has left their hands ("In review").
 */
export interface EditorFrameCopy {
  /** The line above the campaign's title. */
  readonly eyebrow: string;
  /** Drawn in place of the title while the project is still being fetched. */
  readonly loading: string;
  /** The section navigation's accessible name. */
  readonly sections: string;
  /** The visible cue on a section that is not built yet. */
  readonly soon: string;
  /**
   * The spoken half of the same cue, appended to the tab's own name.
   *
   * IT STARTS WITH A COMMA ON PURPOSE. An accessible name is the concatenation of its parts
   * with each part trimmed and no separator inserted, so a leading space is dropped and the
   * name reads "Rewardsnot available yet".
   */
  readonly unavailable: string;
  readonly tabs: Readonly<Record<EditorTabKey, string>>;
  readonly states: Readonly<Record<ProjectState, string>>;
  readonly save: {
    readonly saving: string;
    readonly saved: string;
    readonly failed: string;
  };
}

/** The tab keys, in the order `EDITOR_TABS` declares them. */
const TAB_KEYS: readonly EditorTabKey[] = [
  'basics',
  'rewards',
  'story',
  'faq',
  'prelaunch',
  'review',
];

/**
 * The sixteen states, listed here rather than derived.
 *
 * A union type cannot be enumerated at runtime, and the alternative — reading the keys of
 * whatever the catalogue happens to hold — would resolve a state the application does not have
 * and miss one it does. This list is checked against `ProjectState` by the compiler.
 */
const STATE_KEYS: readonly ProjectState[] = [
  'DRAFT',
  'PRELAUNCH',
  'SUBMITTED',
  'CHANGES_REQUESTED',
  'REJECTED',
  'APPROVED',
  'SCHEDULED',
  'LIVE',
  'SUSPENDED',
  'CANCELED',
  'SUCCESSFUL',
  'UNSUCCESSFUL',
  'COLLECTING',
  'LATE_PLEDGE',
  'FULFILLING',
  'COMPLETED',
];

function record<K extends string>(
  keys: readonly K[],
  read: (key: K) => string,
): Readonly<Record<K, string>> {
  return Object.fromEntries(keys.map((key) => [key, read(key)])) as Record<K, string>;
}

export function editorFrameCopyFrom(t: EditorTranslator): EditorFrameCopy {
  return {
    eyebrow: t('frame.eyebrow'),
    loading: t('frame.loading'),
    sections: t('frame.sections'),
    soon: t('frame.soon'),
    unavailable: t('frame.unavailable'),
    tabs: record(TAB_KEYS, (key) => t(`frame.tabs.${key}`)),
    states: record(STATE_KEYS, (key) => t(`frame.states.${key}`)),
    save: {
      saving: t('frame.save.saving'),
      saved: t('frame.save.saved'),
      failed: t('frame.save.failed'),
    },
  };
}

/* -------------------------------------------------------------------------
 * One shape per tab
 *
 * Each is the frame plus that tab's own words, and each is resolved by the page that renders
 * the tab. Handing every panel the whole editor's vocabulary would put the reward drawer's
 * sixty-seven strings into the flight payload of the screen where somebody is typing a title.
 * ---------------------------------------------------------------------- */

export interface BasicsCopy {
  readonly frame: EditorFrameCopy;
}

export interface RewardsCopy {
  readonly frame: EditorFrameCopy;
}

export interface StoryCopy {
  readonly frame: EditorFrameCopy;
}

export interface FaqCopy {
  readonly frame: EditorFrameCopy;
}

export interface PrelaunchCopy {
  readonly frame: EditorFrameCopy;
}

export interface ReviewCopy {
  readonly frame: EditorFrameCopy;
}

export function basicsCopyFrom(t: EditorTranslator): BasicsCopy {
  return { frame: editorFrameCopyFrom(t) };
}

export function rewardsCopyFrom(t: EditorTranslator): RewardsCopy {
  return { frame: editorFrameCopyFrom(t) };
}

export function storyCopyFrom(t: EditorTranslator): StoryCopy {
  return { frame: editorFrameCopyFrom(t) };
}

export function faqCopyFrom(t: EditorTranslator): FaqCopy {
  return { frame: editorFrameCopyFrom(t) };
}

export function prelaunchCopyFrom(t: EditorTranslator): PrelaunchCopy {
  return { frame: editorFrameCopyFrom(t) };
}

export function reviewCopyFrom(t: EditorTranslator): ReviewCopy {
  return { frame: editorFrameCopyFrom(t) };
}
