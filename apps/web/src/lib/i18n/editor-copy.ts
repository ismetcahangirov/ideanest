import type { AmountRejection } from '../money';
import type { UploadStage } from '../media/upload';
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

/**
 * What is wrong with what a creator typed — `lib/projects/basics.ts`.
 *
 * <p>IT IS AN ARGUMENT TO `validateBasics` RATHER THAN SOMETHING THAT MODULE LOOKS UP, the way
 * `lib/auth/failures.ts` takes `AuthFailuresCopy`. The rules are §5.3's and belong in a pure
 * function that can be tested at its boundaries — sixty characters, one day, sixty days — and a
 * pure function cannot read a catalogue. Making the argument required rather than optional is
 * deliberate: an optional one would leave the goal field quietly answering in English, on the
 * figure the whole campaign is measured against.
 */
export interface BasicsErrorsCopy {
  readonly titleMissing: string;
  /** Carries `{max}` and `{over}`. */
  readonly titleTooLong: string;
  /** Carries `{max}` and `{over}`. */
  readonly blurbTooLong: string;
  readonly subcategoryWithoutCategory: string;
  readonly currencyUnsupported: string;
  readonly durationNotWhole: string;
  /** Carries `{min}` and `{max}`. */
  readonly durationOutOfRange: string;
  readonly scheduleUnreadable: string;
  readonly schedulePast: string;
  /** One sentence per way `parseAmount` can refuse a figure. */
  readonly amount: Readonly<Record<AmountRejection, string>>;
}

/** `CoverImageField`, which is most of this tab's vocabulary on its own. */
export interface CoverImageCopy {
  readonly label: string;
  /** Carries `{minimum}`. */
  readonly hint: string;
  /** Carries `{size}`. */
  readonly set: string;
  /** Appended to {@link set} when the file came from an upload rather than an address. */
  readonly uploaded: string;
  readonly remove: string;
  readonly drop: string;
  readonly release: string;
  readonly choose: string;
  /** Carries `{minimum}`. */
  readonly dropHint: string;
  readonly addressLabel: string;
  readonly addressPlaceholder: string;
  readonly checking: string;
  readonly useAddress: string;
  readonly addressMissing: string;
  /** Carries `{size}`. */
  readonly accepted: string;
  readonly softTitle: string;
  /** Carries `{size}` and `{minimum}`. */
  readonly soft: string;
  readonly rejectedTitle: string;
  readonly unusable: string;
  readonly stages: Readonly<Record<UploadStage, string>>;
  /**
   * What each refusal means, keyed on the service's own code.
   *
   * <p>The code rather than the sentence, because the sentence the service writes is English
   * for a log and these are read by somebody deciding what to do next. An unknown code falls
   * back to whatever the service said, which is the honest failure: a wrong sentence in the
   * right language would be worse.
   *
   * <p>`TOO_SMALL` carries `{minimum}`.
   */
  readonly refusals: Readonly<Record<string, string>>;
}

export interface BasicsCopy {
  readonly frame: EditorFrameCopy;
  readonly signedOut: { readonly title: string; readonly body: string };
  readonly loadFailed: { readonly title: string };
  readonly tryAgain: string;
  readonly loading: string;
  readonly saveFailed: { readonly title: string; readonly kept: string };
  /** Carries `{max}` on the hint. */
  readonly title: { readonly label: string; readonly hint: string };
  /** Carries `{max}` on the hint. */
  readonly blurb: { readonly label: string; readonly hint: string };
  readonly categoriesUnavailable: { readonly title: string; readonly body: string };
  readonly category: {
    readonly label: string;
    readonly hint: string;
    readonly placeholder: string;
  };
  readonly subcategory: {
    readonly label: string;
    readonly placeholder: string;
    readonly chooseCategory: string;
    readonly none: string;
    readonly optional: string;
  };
  readonly goal: { readonly label: string; readonly hint: string; readonly locked: string };
  readonly currency: { readonly label: string; readonly hint: string };
  /** Carries `{min}`, `{max}` and `{recommended}` on the hint. */
  readonly duration: { readonly label: string; readonly hint: string; readonly locked: string };
  readonly schedule: { readonly label: string; readonly hint: string };
  readonly latePledge: { readonly label: string; readonly hint: string };
  readonly errors: BasicsErrorsCopy;
  readonly cover: CoverImageCopy;
}

/**
 * The six ways `parseAmount` refuses a figure, paired with the key that explains each.
 *
 * The rejection reasons are hyphenated — they are the module's own vocabulary — and message
 * keys are not, so the two are joined here rather than by building a key out of a string.
 */
const AMOUNT_KEYS: ReadonlyArray<readonly [AmountRejection, string]> = [
  ['empty', 'empty'],
  ['not-a-number', 'notANumber'],
  ['comma', 'comma'],
  ['too-many-decimals', 'tooManyDecimals'],
  ['too-large', 'tooLarge'],
  ['not-positive', 'notPositive'],
];

const UPLOAD_STAGES: readonly UploadStage[] = ['preparing', 'uploading', 'processing'];

/**
 * The refusal codes `POST /v1/media` can answer with.
 *
 * Listed rather than read off the catalogue so that a code with no message is a missing key at
 * build time instead of a blank alert in front of somebody whose photograph was rejected.
 */
const REFUSAL_CODES: readonly string[] = [
  'UNSUPPORTED_FORMAT',
  'TOO_LARGE',
  'TOO_SMALL',
  'EMPTY',
  'UNREADABLE',
  'UPLOADS_UNAVAILABLE',
  'MEDIA_STORAGE_UNREACHABLE',
  'UPLOAD_STILL_PROCESSING',
  'UPLOAD_TRANSFER_FAILED',
];

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
  /**
   * The same refusal vocabulary the basics tab uses.
   *
   * The pre-launch tab edits a subset of the same fields — the title and the summary a
   * pre-launch page shows — through `validateBasics`, so it needs the same sentences. One
   * vocabulary rather than two, because a title refused on one tab and accepted on the other
   * would be the same rule stated twice and eventually differently.
   */
  readonly errors: BasicsErrorsCopy;
  /**
   * And the same cover-image vocabulary, for the same reason.
   *
   * A pre-launch page is the campaign before it opens, and it shows the same cover; the tab
   * draws `CoverImageField` to set it.
   */
  readonly cover: CoverImageCopy;
}

export interface ReviewCopy {
  readonly frame: EditorFrameCopy;
}

export function basicsCopyFrom(t: EditorTranslator): BasicsCopy {
  return {
    frame: editorFrameCopyFrom(t),
    signedOut: { title: t('basics.signedOut.title'), body: t('basics.signedOut.body') },
    loadFailed: { title: t('basics.loadFailed.title') },
    tryAgain: t('basics.tryAgain'),
    loading: t('basics.loading'),
    saveFailed: { title: t('basics.saveFailed.title'), kept: t('basics.saveFailed.kept') },
    title: { label: t('basics.title.label'), hint: String(t.raw('basics.title.hint')) },
    blurb: { label: t('basics.blurb.label'), hint: String(t.raw('basics.blurb.hint')) },
    categoriesUnavailable: {
      title: t('basics.categoriesUnavailable.title'),
      body: t('basics.categoriesUnavailable.body'),
    },
    category: {
      label: t('basics.category.label'),
      hint: t('basics.category.hint'),
      placeholder: t('basics.category.placeholder'),
    },
    subcategory: {
      label: t('basics.subcategory.label'),
      placeholder: t('basics.subcategory.placeholder'),
      chooseCategory: t('basics.subcategory.chooseCategory'),
      none: t('basics.subcategory.none'),
      optional: t('basics.subcategory.optional'),
    },
    goal: {
      label: t('basics.goal.label'),
      hint: t('basics.goal.hint'),
      locked: t('basics.goal.locked'),
    },
    currency: { label: t('basics.currency.label'), hint: t('basics.currency.hint') },
    duration: {
      label: t('basics.duration.label'),
      hint: String(t.raw('basics.duration.hint')),
      locked: t('basics.duration.locked'),
    },
    schedule: { label: t('basics.schedule.label'), hint: t('basics.schedule.hint') },
    latePledge: { label: t('basics.latePledge.label'), hint: t('basics.latePledge.hint') },
    errors: {
      titleMissing: t('basics.errors.titleMissing'),
      titleTooLong: String(t.raw('basics.errors.titleTooLong')),
      blurbTooLong: String(t.raw('basics.errors.blurbTooLong')),
      subcategoryWithoutCategory: t('basics.errors.subcategoryWithoutCategory'),
      currencyUnsupported: t('basics.errors.currencyUnsupported'),
      durationNotWhole: t('basics.errors.durationNotWhole'),
      durationOutOfRange: String(t.raw('basics.errors.durationOutOfRange')),
      scheduleUnreadable: t('basics.errors.scheduleUnreadable'),
      schedulePast: t('basics.errors.schedulePast'),
      amount: Object.fromEntries(
        AMOUNT_KEYS.map(([reason, key]) => [reason, t(`basics.errors.amount.${key}`)]),
      ) as Record<AmountRejection, string>,
    },
    cover: {
      label: t('basics.cover.label'),
      hint: String(t.raw('basics.cover.hint')),
      set: String(t.raw('basics.cover.set')),
      uploaded: t('basics.cover.uploaded'),
      remove: t('basics.cover.remove'),
      drop: t('basics.cover.drop'),
      release: t('basics.cover.release'),
      choose: t('basics.cover.choose'),
      dropHint: String(t.raw('basics.cover.dropHint')),
      addressLabel: t('basics.cover.addressLabel'),
      addressPlaceholder: t('basics.cover.addressPlaceholder'),
      checking: t('basics.cover.checking'),
      useAddress: t('basics.cover.useAddress'),
      addressMissing: t('basics.cover.addressMissing'),
      accepted: String(t.raw('basics.cover.accepted')),
      softTitle: t('basics.cover.softTitle'),
      soft: String(t.raw('basics.cover.soft')),
      rejectedTitle: t('basics.cover.rejectedTitle'),
      unusable: t('basics.cover.unusable'),
      stages: record(UPLOAD_STAGES, (stage) => t(`basics.cover.stages.${stage}`)),
      /* Read raw, because `TOO_SMALL` carries `{minimum}` and next-intl refuses a template. */
      refusals: Object.fromEntries(
        REFUSAL_CODES.map((code) => [code, String(t.raw(`basics.cover.refusals.${code}`))]),
      ),
    },
  };
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
  const basics = basicsCopyFrom(t);
  return { frame: basics.frame, errors: basics.errors, cover: basics.cover };
}

export function reviewCopyFrom(t: EditorTranslator): ReviewCopy {
  return { frame: editorFrameCopyFrom(t) };
}
