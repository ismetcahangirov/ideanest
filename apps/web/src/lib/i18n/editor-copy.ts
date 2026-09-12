import type { AmountRejection } from '../money';
import type { UploadStage } from '../media/upload';
import type { PluralForms } from './plurals';
import type { ProjectState } from '../projects/api';
import type { ShippingType } from '../projects/api';
import type { StoryBlockType } from '../projects/story';
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
  /**
   * The three states every tab can be in before it can draw anything.
   *
   * They moved here from `editor.basics.*` when the second tab needed them — issue #459. Six
   * copies of "You are signed out" is six chances for one of them to be translated differently
   * from the other five, on a sentence that is identical whichever tab the session expired on.
   */
  readonly signedOut: { readonly title: string; readonly body: string };
  readonly loadFailed: string;
  readonly tryAgain: string;
  readonly tabs: Readonly<Record<EditorTabKey, string>>;
  readonly states: Readonly<Record<ProjectState, string>>;
  readonly save: {
    readonly saving: string;
    readonly saved: string;
    readonly failed: string;
  };
  /**
   * What the client says when the service said nothing — `useProjectEdit` and `useAutosave`.
   *
   * <p>On the frame rather than on a tab because both hooks are used by all six, and six
   * spellings of "You have been signed out" would be six chances for one of them to say
   * something else. The service's own `detail` is still preferred wherever it wrote one
   * (§10.4): a problem detail written by the endpoint knows which of its rules was broken, and
   * these functions cannot.
   */
  readonly failures: {
    readonly save: {
      readonly signedOut: string;
      readonly notAllowed: string;
      readonly gone: string;
      readonly stale: string;
      readonly invalid: string;
      readonly unsaved: string;
      readonly unreachable: string;
    };
    readonly load: {
      readonly forbidden: string;
      readonly notFound: string;
      readonly refused: string;
      readonly unreachable: string;
    };
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
    signedOut: { title: t('frame.signedOut.title'), body: t('frame.signedOut.body') },
    loadFailed: t('frame.loadFailed'),
    tryAgain: t('frame.tryAgain'),
    tabs: record(TAB_KEYS, (key) => t(`frame.tabs.${key}`)),
    states: record(STATE_KEYS, (key) => t(`frame.states.${key}`)),
    save: {
      saving: t('frame.save.saving'),
      saved: t('frame.save.saved'),
      failed: t('frame.save.failed'),
    },
    failures: {
      save: {
        signedOut: t('frame.failures.save.signedOut'),
        notAllowed: t('frame.failures.save.notAllowed'),
        gone: t('frame.failures.save.gone'),
        stale: t('frame.failures.save.stale'),
        invalid: t('frame.failures.save.invalid'),
        unsaved: t('frame.failures.save.unsaved'),
        unreachable: t('frame.failures.save.unreachable'),
      },
      load: {
        forbidden: t('frame.failures.load.forbidden'),
        notFound: t('frame.failures.load.notFound'),
        refused: t('frame.failures.load.refused'),
        unreachable: t('frame.failures.load.unreachable'),
      },
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

/**
 * What is wrong with an item a creator typed — `validateItem`.
 *
 * Same arrangement as {@link BasicsErrorsCopy} and for the same reason: the rules are the
 * module's and the sentences are the catalogue's, so the sentences arrive as an argument.
 */
export interface ItemErrorsCopy {
  readonly nameMissing: string;
  /** Carries `{max}` and `{over}`. */
  readonly nameTooLong: string;
  /** Carries `{max}`. */
  readonly skuTooLong: string;
  readonly weightOnDigital: string;
  readonly weightNotWhole: string;
  readonly weightNotPositive: string;
}

/** What is wrong with a reward tier — `validateReward`. */
export interface RewardErrorsCopy {
  readonly titleMissing: string;
  /** Carries `{max}` and `{over}`. */
  readonly titleTooLong: string;
  /**
   * Worded for a price rather than for a funding goal.
   *
   * `parseAmount` returns a reason and not a sentence exactly so that the two can differ:
   * "Enter the goal in digits" is wrong on a field labelled Price, and a creator reading it
   * wonders which field the message is about.
   */
  readonly price: Readonly<Record<AmountRejection, string>>;
  /** And again for a shipping rate, where zero is a real offer rather than a refusal. */
  readonly rate: Readonly<Record<AmountRejection, string>>;
  readonly limitNotWhole: string;
  readonly limitTooSmall: string;
  /** Carries `{committed}`. */
  readonly limitBelowCommitted: string;
  readonly secretFeatured: string;
  readonly earlyBirdNeedsLimit: string;
  readonly dateUnreadable: string;
  readonly closesBeforeOpens: string;
  readonly itemsDuplicate: string;
  readonly itemsQuantity: string;
  readonly rulesNotShipped: string;
  readonly rulesCountryCode: string;
  /** Carries `{code}`. */
  readonly rulesDuplicate: string;
  /** Carries `{code}` and `{reason}` — a rate's own refusal, named by its destination. */
  readonly rulesRow: string;
}

/** The five delivery scopes, each a word and the sentence that separates it from the next. */
export interface ShippingScopeCopy {
  readonly label: string;
  readonly hint: string;
}

export interface RewardsCopy {
  readonly frame: EditorFrameCopy;
  readonly loading: string;
  readonly listFailed: string;
  readonly actionFailed: string;
  readonly kept: string;
  /** Carries `{tiers}`; the count is how many tiers an `ITEM_IN_USE` refusal named. */
  readonly itemInUse: PluralForms;
  readonly rewardHasBackers: string;
  readonly heading: string;
  /** Carries `{count}` and `{max}`. */
  readonly count: string;
  readonly add: string;
  readonly fullTitle: string;
  /** Carries `{max}`. */
  readonly fullBody: string;
  readonly empty: { readonly title: string; readonly body: string; readonly action: string };
  readonly listLabel: string;
  /**
   * The live region's sentences — every action on this page whose only other evidence is the
   * list visibly rearranging itself. Each carries `{title}`, and the first two a position.
   */
  readonly announce: {
    readonly moved: string;
    readonly duplicated: string;
    readonly hidden: string;
    readonly shown: string;
    readonly deleted: string;
  };
  readonly card: {
    readonly hidden: string;
    readonly opensLater: string;
    readonly featured: string;
    readonly secret: string;
    readonly earlyBird: string;
    readonly addon: string;
    /** Carries `{month}`. */
    readonly delivers: string;
    /** Each carries `{title}`, `{position}` and `{total}`. */
    readonly moveUp: string;
    readonly moveDown: string;
    readonly edit: string;
    readonly editLabel: string;
    readonly duplicate: string;
    readonly duplicateLabel: string;
    readonly show: string;
    readonly showLabel: string;
    readonly hide: string;
    readonly hideLabel: string;
    readonly delete: string;
    readonly deleteLabel: string;
    readonly backed: PluralForms;
  };
  readonly contents: {
    readonly none: string;
    /** Carries `{items}`. */
    readonly some: string;
    /** Carries `{name}` and `{quantity}`. */
    readonly quantity: string;
    readonly missing: string;
  };
  readonly namedTiers: { readonly unknown: string; readonly one: string };
  readonly deleteItem: {
    readonly title: string;
    /** Carries `{name}`. */
    readonly named: string;
    readonly body: string;
  };
  readonly deleteReward: {
    readonly title: string;
    /** Carries `{title}`. */
    readonly named: string;
    readonly body: string;
  };
  readonly deleteNote: string;
  readonly keepIt: string;
  readonly confirmDelete: string;
  readonly items: {
    readonly heading: string;
    /** Carries `{count}`. */
    readonly count: string;
    readonly add: string;
    readonly loading: string;
    readonly empty: { readonly title: string; readonly body: string; readonly action: string };
    readonly digital: string;
    readonly physical: string;
    /** Carries `{weight}`. */
    readonly grams: string;
    /**
     * The two control words, which are the card's own keys read a second time.
     *
     * "Edit" on the control that edits an item and "Edit" on the control that edits a reward
     * are the same word for the same gesture, and a second key would be a second chance for a
     * translator to pick a different verb for one of them.
     */
    readonly edit: string;
    readonly delete: string;
    /** Each carries `{name}`. */
    readonly editLabel: string;
    readonly deleteLabel: string;
  };
  /** `EditorDrawer`'s footer, shared by both editors in this tab. */
  readonly drawer: {
    readonly cancel: string;
    readonly save: string;
    readonly saving: string;
  };
  readonly itemEditor: {
    readonly titleNew: string;
    readonly titleEdit: string;
    readonly intro: string;
    readonly failed: string;
    /** The name's hint carries `{max}`. */
    readonly name: { readonly label: string; readonly hint: string };
    readonly description: { readonly label: string; readonly hint: string };
    readonly image: { readonly label: string; readonly hint: string };
    readonly digital: { readonly label: string; readonly hint: string };
    readonly weight: {
      readonly label: string;
      readonly hint: string;
      readonly digital: string;
    };
    readonly sku: { readonly label: string; readonly hint: string };
  };
  readonly itemErrors: ItemErrorsCopy;
  readonly tierEditor: {
    readonly titleNew: string;
    readonly titleEdit: string;
    readonly intro: string;
    readonly failed: string;
    readonly ratesFailed: string;
    /**
     * Two sentences rather than one with a tag in the middle.
     *
     * The first is emphasised, because a creator who has just been told a save failed has to
     * read that the reward itself is safe before they retype it. A rich-text tag would put the
     * emphasis at a fixed point in an English sentence; two sentences let a translator put the
     * words where their own language puts them.
     */
    readonly ratesKeptLead: string;
    readonly ratesKeptRest: string;
    /** The title's hint carries `{max}`. */
    readonly title: { readonly label: string; readonly hint: string };
    readonly description: { readonly label: string; readonly hint: string };
    /** Both price hints carry `{currency}`. */
    readonly price: { readonly label: string; readonly hint: string; readonly locked: string };
    readonly delivery: { readonly label: string; readonly hint: string };
    /** `committed` carries `{committed}`. */
    readonly places: {
      readonly label: string;
      readonly hint: string;
      readonly committed: string;
    };
    readonly shipping: { readonly label: string };
  };
  readonly scopes: Readonly<Record<ShippingType, ShippingScopeCopy>>;
  readonly rates: {
    readonly label: string;
    /** Carries `{currency}`. */
    readonly hint: string;
    readonly empty: string;
    readonly add: string;
    /** Carries `{position}` — what a row with no destination yet is called. */
    readonly unnamed: string;
    /** Each carries `{destination}`. */
    readonly countryLabel: string;
    readonly ratePlaceholder: string;
    readonly rateLabel: string;
    readonly extraPlaceholder: string;
    readonly extraLabel: string;
    readonly removeLabel: string;
  };
  readonly composition: {
    readonly label: string;
    readonly hint: string;
    readonly missing: string;
    /** Each carries `{name}`. */
    readonly quantityLabel: string;
    readonly removeLabel: string;
    readonly addPlaceholder: string;
    readonly addLabel: string;
    readonly noItems: string;
    readonly allChosen: string;
  };
  readonly opens: { readonly label: string; readonly hint: string };
  readonly closes: { readonly label: string; readonly hint: string };
  readonly offering: { readonly legend: string };
  readonly earlyBird: { readonly label: string; readonly hint: string };
  readonly featured: { readonly label: string; readonly hint: string };
  readonly secret: { readonly label: string; readonly hint: string; readonly token: string };
  readonly addon: { readonly label: string; readonly hint: string };
  readonly errors: RewardErrorsCopy;
  readonly stock: {
    readonly unlimited: string;
    /** Carries `{count}` and `{limit}`. */
    readonly remaining: PluralForms;
  };
  readonly showBlocked: string;
}

const SHIPPING_TYPES: readonly ShippingType[] = [
  'NONE',
  'DIGITAL',
  'LOCAL_PICKUP',
  'DOMESTIC',
  'INTERNATIONAL',
];

/**
 * What is wrong with one story block — `blockProblem` and `storyProblems`.
 *
 * <p>The client's copy of the server's rules, so a creator learns about a missing image
 * description while they are looking at the image rather than from a failed autosave a second
 * later. Not the authority: `StoryDocuments` is. The sentences arrive as an argument for the
 * reason {@link BasicsErrorsCopy} gives.
 */
export interface StoryProblemsCopy {
  readonly headingText: string;
  readonly anchorUnusable: string;
  readonly anchorDuplicate: string;
  readonly imageAddress: string;
  readonly addressScheme: string;
  readonly imageUnmeasured: string;
  readonly imageAlt: string;
  readonly embedAddress: string;
  readonly embedTitle: string;
}

/**
 * How a block is announced: what kind it is, where it is, and enough of its contents to tell
 * it from its neighbours.
 *
 * <p>Every add, move and remove control in the editor carries one of these. "Move up" repeated
 * eleven times in a row is a screen reader reading out eleven identical buttons, and the
 * position is the only thing that distinguishes them — so the position is in the name.
 *
 * <p>The two list forms are plural, because a list names how many items it holds.
 */
export interface StoryDescribeCopy {
  readonly empty: string;
  /** Each carries `{position}` and `{total}`. */
  readonly heading: string;
  readonly paragraph: string;
  readonly quote: string;
  readonly rule: string;
  readonly image: string;
  readonly imageNoAlt: string;
  /** Also carries `{provider}`. */
  readonly embed: string;
  readonly embedNoTitle: string;
  /** Also carry `{count}`. */
  readonly listBulleted: PluralForms;
  readonly listOrdered: PluralForms;
}

export interface StoryCopy {
  readonly frame: EditorFrameCopy;
  readonly loading: string;
  readonly unreadable: { readonly title: string; readonly body: string; readonly reload: string };
  readonly saveFailed: { readonly title: string; readonly kept: string };
  /** The body carries `{count}`. */
  readonly blocked: { readonly title: string; readonly body: PluralForms };
  /** Carries `{count}` and `{minimum}`. */
  readonly counter: string;
  readonly earlierVersions: string;
  readonly anchors: { readonly heading: string; readonly untitled: string };
  /** The hint carries `{minimum}`. */
  readonly risks: {
    readonly label: string;
    readonly hint: string;
    readonly placeholder: string;
  };
  readonly blocks: {
    readonly heading: string;
    readonly empty: string;
    readonly addHeading: string;
    /** Carries `{position}` and `{total}`. */
    readonly position: string;
    /** Each carries `{name}`. */
    readonly moveUp: string;
    readonly moveDown: string;
    readonly remove: string;
  };
  readonly blockLabel: Readonly<Record<StoryBlockType, string>>;
  /** The whole accessible name of each add control: the verb and what the block is for. */
  readonly addLabel: Readonly<Record<StoryBlockType, string>>;
  readonly announce: {
    /** Both carry `{block}`, `{position}` and `{total}`. */
    readonly added: string;
    readonly moved: string;
    /** Carries `{block}` and `{count}`. */
    readonly removed: PluralForms;
  };
  readonly describe: StoryDescribeCopy;
  readonly problems: StoryProblemsCopy;
  readonly fields: {
    /** Every one of these carries `{name}` — the block it belongs to. */
    readonly headingLevel: string;
    readonly headingSection: string;
    readonly headingSubsection: string;
    readonly headingText: string;
    readonly headingPlaceholder: string;
    readonly paragraphPlaceholder: string;
    readonly quotePlaceholder: string;
    readonly ruleNote: string;
    readonly listStyle: string;
    readonly listBulleted: string;
    readonly listOrdered: string;
    /** Also carry `{position}` and `{total}`. */
    readonly listItem: string;
    readonly listRemoveItem: string;
    readonly listAddItem: string;
    readonly listAddItemLabel: string;
    readonly embedProvider: string;
    readonly embedAddress: string;
    readonly embedAddressPlaceholder: string;
    readonly embedTitle: string;
    readonly embedTitlePlaceholder: string;
    readonly embedNote: string;
  };
  readonly image: {
    readonly noUploadTitle: string;
    readonly noUploadBody: string;
    /** Carries `{name}`. */
    readonly address: string;
    readonly addressPlaceholder: string;
    readonly measure: string;
    readonly measuring: string;
    readonly addressMissing: string;
    /** Both carry `{size}`. */
    readonly added: string;
    readonly pixels: string;
    readonly unreadable: string;
    /** Carries `{name}`. */
    readonly alt: string;
    readonly altPlaceholder: string;
    readonly altNote: string;
  };
  readonly marks: {
    /** Carries `{label}`. */
    readonly group: string;
    readonly bold: string;
    readonly italic: string;
    /** Carries `{name}` and `{shortcut}` — the shortcut is a key name, not a word. */
    readonly named: string;
  };
  readonly history: {
    readonly title: string;
    readonly intro: string;
    readonly loading: string;
    readonly failed: string;
    readonly emptyTitle: string;
    readonly emptyBody: string;
    /** Each carries `{number}`. */
    readonly version: string;
    readonly mostRecent: string;
    /** Carries `{count}`. */
    readonly characters: string;
    readonly preview: string;
    readonly previewLabel: string;
    readonly hide: string;
    readonly restore: string;
    readonly restoreLabel: string;
    readonly loadingVersion: string;
    readonly previewNewer: string;
    readonly historyFailed: string;
    readonly versionFailed: string;
    readonly restoreFailed: string;
    readonly unreachable: string;
    readonly confirmTitle: string;
    readonly confirmNamed: string;
    readonly confirmIntro: string;
    readonly keep: string;
    readonly confirm: string;
    readonly restoring: string;
    /** Carries `{number}`, `{when}` and `{characters}`. */
    readonly savedAt: string;
    /** Carries `{characters}`. */
    readonly currentHolds: string;
    readonly previewEmpty: string;
    /** Carries `{count}` and `{characters}`. */
    readonly previewSummary: PluralForms;
    /**
     * Two vocabularies this drawer borrows rather than repeats.
     *
     * The preview lists a version's blocks by kind, which is the same seven words the editor
     * behind it uses; and a failed load offers the same "Try again" every other surface in the
     * editor offers. Both are read from their own keys, so there is one spelling of each.
     */
    readonly blockLabel: Readonly<Record<StoryBlockType, string>>;
    readonly tryAgain: string;
  };
}

const BLOCK_TYPES: readonly StoryBlockType[] = [
  'heading',
  'paragraph',
  'list',
  'quote',
  'rule',
  'image',
  'embed',
];

/** What is wrong with a question or its answer — `validateFaq`. */
export interface FaqErrorsCopy {
  readonly questionMissing: string;
  readonly answerMissing: string;
  /** Both carry `{count}` — how many characters over — and `{max}`. */
  readonly questionTooLong: PluralForms;
  readonly answerTooLong: PluralForms;
}

/**
 * `FAQ_ORDER_INCOMPLETE`, as a sentence about questions rather than identifiers.
 *
 * <p>FOUR WHOLE SENTENCES RATHER THAN CLAUSES JOINED WITH "and". The refusal has two
 * independent halves — entries the service holds that the order left out, and identifiers the
 * order carried that the service does not have — and the English version composed them by
 * gluing fragments together. That is English syntax written into a builder: the conjunction,
 * the comma and the order of the two clauses are all things another language does differently.
 * So each combination is its own message, and the component picks one.
 */
export interface FaqOrderRefusalCopy {
  /** Carries `{missing}`. */
  readonly missing: string;
  /** Carries `{unexpected}`. */
  readonly unexpected: string;
  /** Carries both. */
  readonly both: string;
  readonly unknown: string;
  /** Always appended, whichever of the four was chosen. */
  readonly reread: string;
  /** Joins the last two names in a list: "a, b and c". */
  readonly conjunction: string;
  /** Carries `{count}` — identifiers this page has never seen and therefore cannot name. */
  readonly others: PluralForms;
}

export interface FaqCopy {
  readonly frame: EditorFrameCopy;
  /**
   * The drawer footer's three words, which the FAQ tab shares with the rewards tab.
   *
   * `EditorDrawer` is one component with one save model, so it has one vocabulary. Two copies
   * of "Save" would be two chances for a translator to pick a different verb for the same
   * control depending on which drawer it was in.
   */
  readonly drawer: RewardsCopy['drawer'];
  readonly loading: string;
  readonly listFailed: string;
  readonly actionFailed: string;
  readonly heading: string;
  /** Carries `{count}`. */
  readonly count: string;
  readonly intro: string;
  readonly add: string;
  readonly fullTitle: string;
  /** Carries `{max}`. */
  readonly fullBody: string;
  readonly empty: { readonly title: string; readonly body: string; readonly action: string };
  readonly listLabel: string;
  /** Both carry `{question}`; the first also `{position}` and `{total}`. */
  readonly announce: { readonly moved: string; readonly deleted: string };
  readonly row: {
    /** Every one of these carries `{question}`. */
    readonly moveUp: string;
    readonly moveDown: string;
    readonly edit: string;
    readonly editLabel: string;
    readonly delete: string;
    readonly deleteLabel: string;
  };
  readonly delete: {
    readonly title: string;
    /** Carries `{question}`. */
    readonly named: string;
    readonly note: string;
    readonly keep: string;
    readonly confirm: string;
    readonly body: string;
  };
  readonly orderRefusal: FaqOrderRefusalCopy;
  readonly editor: {
    readonly titleNew: string;
    readonly titleEdit: string;
    readonly intro: string;
    readonly failed: string;
    readonly kept: string;
    /** Both hints carry `{max}`. */
    readonly question: { readonly label: string; readonly hint: string };
    readonly answer: { readonly label: string; readonly hint: string };
  };
  readonly errors: FaqErrorsCopy;
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
  readonly loading: string;
  readonly saveFailed: { readonly title: string; readonly kept: string };
  readonly notOpen: {
    readonly heading: string;
    readonly body: string;
    readonly permanent: string;
    readonly failed: string;
    readonly action: string;
  };
  readonly live: {
    readonly heading: string;
    readonly countUnavailable: string;
    /** Carries `{count}`. */
    readonly waiting: PluralForms;
    readonly linkLabel: string;
    readonly linkHint: string;
    readonly copy: string;
    readonly copied: string;
    readonly copiedAnnounced: string;
  };
  readonly closed: { readonly title: string; readonly body: string };
  /** Both hints carry `{max}`. The two labels are the basics tab's own. */
  readonly form: {
    readonly heading: string;
    readonly intro: string;
    readonly titleLabel: string;
    readonly titleHint: string;
    readonly blurbLabel: string;
    readonly blurbHint: string;
  };
  readonly confirm: {
    readonly title: string;
    readonly intro: string;
    readonly body: string;
    readonly cancel: string;
    readonly action: string;
    readonly opening: string;
  };
}

export interface ReviewCopy {
  readonly frame: EditorFrameCopy;
  readonly loading: string;
  readonly loadFailed: string;
  readonly moderation: {
    readonly rejected: string;
    readonly changes: string;
    readonly noReason: string;
  };
  /**
   * What a campaign in this state is waiting for, said plainly.
   *
   * Five of the sixteen, because the other eleven have nothing to tell a creator on this tab.
   * Partial on purpose: a note for every state would mean writing one for `COLLECTING`, where
   * the honest answer is that this tab has nothing to say.
   */
  readonly stateNote: Partial<Readonly<Record<ProjectState, string>>>;
  readonly refusal: {
    readonly title: string;
    /** Carries `{label}` and `{detail}` — both the server's own words. */
    readonly item: string;
    readonly plans: string;
    readonly checkAgain: string;
  };
  readonly progress: {
    readonly heading: string;
    /** Carries `{score}` and the four counts. */
    readonly summary: string;
    /** Carries `{score}`. */
    readonly barLabel: string;
  };
  readonly blocking: { readonly heading: string; readonly description: string };
  readonly advisory: { readonly heading: string; readonly description: string };
  readonly row: {
    readonly done: string;
    readonly requiredNotDone: string;
    readonly recommendedNotDone: string;
    /** Carries `{status}`; the leading comma is load-bearing in an accessible name. */
    readonly status: string;
    /** Carries `{section}`. */
    readonly fix: string;
    /** Carries `{label}`. */
    readonly fixDetail: string;
  };
  readonly submit: {
    readonly heading: string;
    readonly submitting: string;
    readonly ready: string;
    /** Both carry `{count}` — how many required items are still not done. */
    readonly held: PluralForms;
    readonly heldWithSuggestions: PluralForms;
  };
  readonly launch: {
    readonly heading: string;
    readonly failed: string;
    readonly confirmHeading: string;
    readonly confirmBody: string;
    readonly now: string;
    readonly launching: string;
    readonly cancel: string;
    readonly explanation: string;
  };
  /**
   * What the client says when the service said nothing.
   *
   * The service's own sentence is preferred wherever it wrote one (§10.4) — it knows which of
   * its rules was broken. These are the statuses that arrive without one.
   */
  readonly failures: {
    readonly notFound: string;
    readonly planLimit: string;
    readonly forbidden: string;
    readonly refused: string;
    readonly unreachable: string;
  };
  /**
   * The three sections a checklist row can point at, which are three of the editor's own tabs.
   *
   * Read from `frame.tabs` rather than given keys of their own: "Fix in Basics" names the tab
   * the creator is about to open, and a second spelling would be the same tab called two things
   * on two screens.
   */
  readonly sections: Readonly<Record<'basics' | 'rewards' | 'story', string>>;
}

export function basicsCopyFrom(t: EditorTranslator): BasicsCopy {
  return {
    frame: editorFrameCopyFrom(t),
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
  const amounts = (group: string): Readonly<Record<AmountRejection, string>> =>
    Object.fromEntries(
      AMOUNT_KEYS.map(([reason, key]) => [reason, t(`rewards.errors.${group}.${key}`)]),
    ) as Record<AmountRejection, string>;

  return {
    frame: editorFrameCopyFrom(t),
    loading: t('rewards.loading'),
    listFailed: t('rewards.listFailed'),
    actionFailed: t('rewards.actionFailed'),
    kept: t('rewards.kept'),
    itemInUse: t.raw('rewards.itemInUse') as PluralForms,
    rewardHasBackers: t('rewards.rewardHasBackers'),
    heading: t('rewards.heading'),
    count: String(t.raw('rewards.count')),
    add: t('rewards.add'),
    fullTitle: t('rewards.fullTitle'),
    fullBody: String(t.raw('rewards.fullBody')),
    empty: {
      title: t('rewards.empty.title'),
      body: t('rewards.empty.body'),
      action: t('rewards.empty.action'),
    },
    listLabel: t('rewards.listLabel'),
    announce: {
      moved: String(t.raw('rewards.announce.moved')),
      duplicated: String(t.raw('rewards.announce.duplicated')),
      hidden: String(t.raw('rewards.announce.hidden')),
      shown: String(t.raw('rewards.announce.shown')),
      deleted: String(t.raw('rewards.announce.deleted')),
    },
    card: {
      hidden: t('rewards.card.hidden'),
      opensLater: t('rewards.card.opensLater'),
      featured: t('rewards.card.featured'),
      secret: t('rewards.card.secret'),
      earlyBird: t('rewards.card.earlyBird'),
      addon: t('rewards.card.addon'),
      delivers: String(t.raw('rewards.card.delivers')),
      moveUp: String(t.raw('rewards.card.moveUp')),
      moveDown: String(t.raw('rewards.card.moveDown')),
      edit: t('rewards.card.edit'),
      editLabel: String(t.raw('rewards.card.editLabel')),
      duplicate: t('rewards.card.duplicate'),
      duplicateLabel: String(t.raw('rewards.card.duplicateLabel')),
      show: t('rewards.card.show'),
      showLabel: String(t.raw('rewards.card.showLabel')),
      hide: t('rewards.card.hide'),
      hideLabel: String(t.raw('rewards.card.hideLabel')),
      delete: t('rewards.card.delete'),
      deleteLabel: String(t.raw('rewards.card.deleteLabel')),
      backed: t.raw('rewards.card.backed') as PluralForms,
    },
    contents: {
      none: t('rewards.contents.none'),
      some: String(t.raw('rewards.contents.some')),
      quantity: String(t.raw('rewards.contents.quantity')),
      missing: t('rewards.contents.missing'),
    },
    namedTiers: {
      unknown: t('rewards.namedTiers.unknown'),
      one: t('rewards.namedTiers.one'),
    },
    deleteItem: {
      title: t('rewards.deleteItem.title'),
      named: String(t.raw('rewards.deleteItem.named')),
      body: t('rewards.deleteItem.body'),
    },
    deleteReward: {
      title: t('rewards.deleteReward.title'),
      named: String(t.raw('rewards.deleteReward.named')),
      body: t('rewards.deleteReward.body'),
    },
    deleteNote: t('rewards.deleteNote'),
    keepIt: t('rewards.keepIt'),
    confirmDelete: t('rewards.confirmDelete'),
    items: {
      heading: t('rewards.items.heading'),
      count: String(t.raw('rewards.items.count')),
      add: t('rewards.items.add'),
      loading: t('rewards.items.loading'),
      empty: {
        title: t('rewards.items.empty.title'),
        body: t('rewards.items.empty.body'),
        action: t('rewards.items.empty.action'),
      },
      digital: t('rewards.items.digital'),
      physical: t('rewards.items.physical'),
      grams: String(t.raw('rewards.items.grams')),
      edit: t('rewards.card.edit'),
      delete: t('rewards.card.delete'),
      editLabel: String(t.raw('rewards.items.editLabel')),
      deleteLabel: String(t.raw('rewards.items.deleteLabel')),
    },
    drawer: {
      cancel: t('rewards.drawer.cancel'),
      save: t('rewards.drawer.save'),
      saving: t('rewards.drawer.saving'),
    },
    itemEditor: {
      titleNew: t('rewards.itemEditor.titleNew'),
      titleEdit: t('rewards.itemEditor.titleEdit'),
      intro: t('rewards.itemEditor.intro'),
      failed: t('rewards.itemEditor.failed'),
      name: {
        label: t('rewards.itemEditor.name.label'),
        hint: String(t.raw('rewards.itemEditor.name.hint')),
      },
      description: {
        label: t('rewards.itemEditor.description.label'),
        hint: t('rewards.itemEditor.description.hint'),
      },
      image: {
        label: t('rewards.itemEditor.image.label'),
        hint: t('rewards.itemEditor.image.hint'),
      },
      digital: {
        label: t('rewards.itemEditor.digital.label'),
        hint: t('rewards.itemEditor.digital.hint'),
      },
      weight: {
        label: t('rewards.itemEditor.weight.label'),
        hint: t('rewards.itemEditor.weight.hint'),
        digital: t('rewards.itemEditor.weight.digital'),
      },
      sku: {
        label: t('rewards.itemEditor.sku.label'),
        hint: t('rewards.itemEditor.sku.hint'),
      },
    },
    itemErrors: {
      nameMissing: t('rewards.itemErrors.nameMissing'),
      nameTooLong: String(t.raw('rewards.itemErrors.nameTooLong')),
      skuTooLong: String(t.raw('rewards.itemErrors.skuTooLong')),
      weightOnDigital: t('rewards.itemErrors.weightOnDigital'),
      weightNotWhole: t('rewards.itemErrors.weightNotWhole'),
      weightNotPositive: t('rewards.itemErrors.weightNotPositive'),
    },
    tierEditor: {
      titleNew: t('rewards.tierEditor.titleNew'),
      titleEdit: t('rewards.tierEditor.titleEdit'),
      intro: t('rewards.tierEditor.intro'),
      failed: t('rewards.tierEditor.failed'),
      ratesFailed: t('rewards.tierEditor.ratesFailed'),
      ratesKeptLead: t('rewards.tierEditor.ratesKeptLead'),
      ratesKeptRest: t('rewards.tierEditor.ratesKeptRest'),
      title: {
        label: t('rewards.tierEditor.title.label'),
        hint: String(t.raw('rewards.tierEditor.title.hint')),
      },
      description: {
        label: t('rewards.tierEditor.description.label'),
        hint: t('rewards.tierEditor.description.hint'),
      },
      price: {
        label: t('rewards.tierEditor.price.label'),
        hint: String(t.raw('rewards.tierEditor.price.hint')),
        locked: String(t.raw('rewards.tierEditor.price.locked')),
      },
      delivery: {
        label: t('rewards.tierEditor.delivery.label'),
        hint: t('rewards.tierEditor.delivery.hint'),
      },
      places: {
        label: t('rewards.tierEditor.places.label'),
        hint: t('rewards.tierEditor.places.hint'),
        committed: String(t.raw('rewards.tierEditor.places.committed')),
      },
      shipping: { label: t('rewards.tierEditor.shipping.label') },
    },
    scopes: Object.fromEntries(
      SHIPPING_TYPES.map((scope) => [
        scope,
        { label: t(`rewards.scopes.${scope}.label`), hint: t(`rewards.scopes.${scope}.hint`) },
      ]),
    ) as Record<ShippingType, ShippingScopeCopy>,
    rates: {
      label: t('rewards.rates.label'),
      hint: String(t.raw('rewards.rates.hint')),
      empty: t('rewards.rates.empty'),
      add: t('rewards.rates.add'),
      unnamed: String(t.raw('rewards.rates.unnamed')),
      countryLabel: String(t.raw('rewards.rates.countryLabel')),
      ratePlaceholder: t('rewards.rates.ratePlaceholder'),
      rateLabel: String(t.raw('rewards.rates.rateLabel')),
      extraPlaceholder: t('rewards.rates.extraPlaceholder'),
      extraLabel: String(t.raw('rewards.rates.extraLabel')),
      removeLabel: String(t.raw('rewards.rates.removeLabel')),
    },
    composition: {
      label: t('rewards.composition.label'),
      hint: t('rewards.composition.hint'),
      missing: t('rewards.composition.missing'),
      quantityLabel: String(t.raw('rewards.composition.quantityLabel')),
      removeLabel: String(t.raw('rewards.composition.removeLabel')),
      addPlaceholder: t('rewards.composition.addPlaceholder'),
      addLabel: t('rewards.composition.addLabel'),
      noItems: t('rewards.composition.noItems'),
      allChosen: t('rewards.composition.allChosen'),
    },
    opens: { label: t('rewards.opens.label'), hint: t('rewards.opens.hint') },
    closes: { label: t('rewards.closes.label'), hint: t('rewards.closes.hint') },
    offering: { legend: t('rewards.offering.legend') },
    earlyBird: { label: t('rewards.earlyBird.label'), hint: t('rewards.earlyBird.hint') },
    featured: { label: t('rewards.featured.label'), hint: t('rewards.featured.hint') },
    secret: {
      label: t('rewards.secret.label'),
      hint: t('rewards.secret.hint'),
      token: t('rewards.secret.token'),
    },
    addon: { label: t('rewards.addon.label'), hint: t('rewards.addon.hint') },
    errors: {
      titleMissing: t('rewards.errors.titleMissing'),
      titleTooLong: String(t.raw('rewards.errors.titleTooLong')),
      price: amounts('price'),
      rate: amounts('rate'),
      limitNotWhole: t('rewards.errors.limitNotWhole'),
      limitTooSmall: t('rewards.errors.limitTooSmall'),
      limitBelowCommitted: String(t.raw('rewards.errors.limitBelowCommitted')),
      secretFeatured: t('rewards.errors.secretFeatured'),
      earlyBirdNeedsLimit: t('rewards.errors.earlyBirdNeedsLimit'),
      dateUnreadable: t('rewards.errors.dateUnreadable'),
      closesBeforeOpens: t('rewards.errors.closesBeforeOpens'),
      itemsDuplicate: t('rewards.errors.itemsDuplicate'),
      itemsQuantity: t('rewards.errors.itemsQuantity'),
      rulesNotShipped: t('rewards.errors.rulesNotShipped'),
      rulesCountryCode: t('rewards.errors.rulesCountryCode'),
      rulesDuplicate: String(t.raw('rewards.errors.rulesDuplicate')),
      rulesRow: String(t.raw('rewards.errors.rulesRow')),
    },
    stock: {
      unlimited: t('rewards.stock.unlimited'),
      remaining: t.raw('rewards.stock.remaining') as PluralForms,
    },
    showBlocked: t('rewards.showBlocked'),
  };
}

export function storyCopyFrom(t: EditorTranslator): StoryCopy {
  const template = (key: string): string => String(t.raw(`story.${key}`));

  return {
    frame: editorFrameCopyFrom(t),
    loading: t('story.loading'),
    unreadable: {
      title: t('story.unreadable.title'),
      body: t('story.unreadable.body'),
      reload: t('story.unreadable.reload'),
    },
    saveFailed: { title: t('story.saveFailed.title'), kept: t('story.saveFailed.kept') },
    blocked: {
      title: t('story.blocked.title'),
      body: t.raw('story.blocked.body') as PluralForms,
    },
    counter: template('counter'),
    earlierVersions: t('story.earlierVersions'),
    anchors: { heading: t('story.anchors.heading'), untitled: t('story.anchors.untitled') },
    risks: {
      label: t('story.risks.label'),
      hint: template('risks.hint'),
      placeholder: t('story.risks.placeholder'),
    },
    blocks: {
      heading: t('story.blocks.heading'),
      empty: t('story.blocks.empty'),
      addHeading: t('story.blocks.addHeading'),
      position: template('blocks.position'),
      moveUp: template('blocks.moveUp'),
      moveDown: template('blocks.moveDown'),
      remove: template('blocks.remove'),
    },
    blockLabel: record(BLOCK_TYPES, (type) => t(`story.blockLabel.${type}`)),
    addLabel: record(BLOCK_TYPES, (type) => t(`story.addLabel.${type}`)),
    announce: {
      added: template('announce.added'),
      moved: template('announce.moved'),
      removed: t.raw('story.announce.removed') as PluralForms,
    },
    describe: {
      empty: t('story.describe.empty'),
      heading: template('describe.heading'),
      paragraph: template('describe.paragraph'),
      quote: template('describe.quote'),
      rule: template('describe.rule'),
      image: template('describe.image'),
      imageNoAlt: t('story.describe.imageNoAlt'),
      embed: template('describe.embed'),
      embedNoTitle: t('story.describe.embedNoTitle'),
      listBulleted: t.raw('story.describe.listBulleted') as PluralForms,
      listOrdered: t.raw('story.describe.listOrdered') as PluralForms,
    },
    problems: {
      headingText: t('story.problems.headingText'),
      anchorUnusable: t('story.problems.anchorUnusable'),
      anchorDuplicate: t('story.problems.anchorDuplicate'),
      imageAddress: t('story.problems.imageAddress'),
      addressScheme: t('story.problems.addressScheme'),
      imageUnmeasured: t('story.problems.imageUnmeasured'),
      imageAlt: t('story.problems.imageAlt'),
      embedAddress: t('story.problems.embedAddress'),
      embedTitle: t('story.problems.embedTitle'),
    },
    fields: {
      headingLevel: template('fields.headingLevel'),
      headingSection: t('story.fields.headingSection'),
      headingSubsection: t('story.fields.headingSubsection'),
      headingText: template('fields.headingText'),
      headingPlaceholder: t('story.fields.headingPlaceholder'),
      paragraphPlaceholder: t('story.fields.paragraphPlaceholder'),
      quotePlaceholder: t('story.fields.quotePlaceholder'),
      ruleNote: t('story.fields.ruleNote'),
      listStyle: template('fields.listStyle'),
      listBulleted: t('story.fields.listBulleted'),
      listOrdered: t('story.fields.listOrdered'),
      listItem: template('fields.listItem'),
      listRemoveItem: template('fields.listRemoveItem'),
      listAddItem: t('story.fields.listAddItem'),
      listAddItemLabel: template('fields.listAddItemLabel'),
      embedProvider: template('fields.embedProvider'),
      embedAddress: template('fields.embedAddress'),
      embedAddressPlaceholder: t('story.fields.embedAddressPlaceholder'),
      embedTitle: template('fields.embedTitle'),
      embedTitlePlaceholder: t('story.fields.embedTitlePlaceholder'),
      embedNote: t('story.fields.embedNote'),
    },
    image: {
      noUploadTitle: t('story.image.noUploadTitle'),
      noUploadBody: t('story.image.noUploadBody'),
      address: template('image.address'),
      addressPlaceholder: t('story.image.addressPlaceholder'),
      measure: t('story.image.measure'),
      measuring: t('story.image.measuring'),
      addressMissing: t('story.image.addressMissing'),
      added: template('image.added'),
      pixels: template('image.pixels'),
      unreadable: t('story.image.unreadable'),
      alt: template('image.alt'),
      altPlaceholder: t('story.image.altPlaceholder'),
      altNote: t('story.image.altNote'),
    },
    marks: {
      group: template('marks.group'),
      bold: t('story.marks.bold'),
      italic: t('story.marks.italic'),
      named: template('marks.named'),
    },
    history: {
      title: t('story.history.title'),
      intro: t('story.history.intro'),
      loading: t('story.history.loading'),
      failed: t('story.history.failed'),
      emptyTitle: t('story.history.emptyTitle'),
      emptyBody: t('story.history.emptyBody'),
      version: template('history.version'),
      mostRecent: t('story.history.mostRecent'),
      characters: template('history.characters'),
      preview: t('story.history.preview'),
      previewLabel: template('history.previewLabel'),
      hide: t('story.history.hide'),
      restore: t('story.history.restore'),
      restoreLabel: template('history.restoreLabel'),
      loadingVersion: t('story.history.loadingVersion'),
      previewNewer: t('story.history.previewNewer'),
      historyFailed: t('story.history.historyFailed'),
      versionFailed: t('story.history.versionFailed'),
      restoreFailed: t('story.history.restoreFailed'),
      unreachable: t('story.history.unreachable'),
      confirmTitle: t('story.history.confirmTitle'),
      confirmNamed: template('history.confirmNamed'),
      confirmIntro: t('story.history.confirmIntro'),
      keep: t('story.history.keep'),
      confirm: t('story.history.confirm'),
      restoring: t('story.history.restoring'),
      savedAt: template('history.savedAt'),
      currentHolds: template('history.currentHolds'),
      previewEmpty: t('story.history.previewEmpty'),
      previewSummary: t.raw('story.history.previewSummary') as PluralForms,
      blockLabel: record(BLOCK_TYPES, (type) => t(`story.blockLabel.${type}`)),
      tryAgain: t('frame.tryAgain'),
    },
  };
}

export function faqCopyFrom(t: EditorTranslator): FaqCopy {
  const template = (key: string): string => String(t.raw(`faq.${key}`));

  return {
    frame: editorFrameCopyFrom(t),
    drawer: {
      cancel: t('rewards.drawer.cancel'),
      save: t('rewards.drawer.save'),
      saving: t('rewards.drawer.saving'),
    },
    loading: t('faq.loading'),
    listFailed: t('faq.listFailed'),
    actionFailed: t('faq.actionFailed'),
    heading: t('faq.heading'),
    count: template('count'),
    intro: t('faq.intro'),
    add: t('faq.add'),
    fullTitle: t('faq.fullTitle'),
    fullBody: template('fullBody'),
    empty: {
      title: t('faq.empty.title'),
      body: t('faq.empty.body'),
      action: t('faq.empty.action'),
    },
    listLabel: t('faq.listLabel'),
    announce: { moved: template('announce.moved'), deleted: template('announce.deleted') },
    row: {
      moveUp: template('row.moveUp'),
      moveDown: template('row.moveDown'),
      edit: t('faq.row.edit'),
      editLabel: template('row.editLabel'),
      delete: t('faq.row.delete'),
      deleteLabel: template('row.deleteLabel'),
    },
    delete: {
      title: t('faq.delete.title'),
      named: template('delete.named'),
      note: t('faq.delete.note'),
      keep: t('faq.delete.keep'),
      confirm: t('faq.delete.confirm'),
      body: t('faq.delete.body'),
    },
    orderRefusal: {
      missing: template('orderRefusal.missing'),
      unexpected: template('orderRefusal.unexpected'),
      both: template('orderRefusal.both'),
      unknown: t('faq.orderRefusal.unknown'),
      reread: t('faq.orderRefusal.reread'),
      conjunction: t('faq.orderRefusal.conjunction'),
      others: t.raw('faq.orderRefusal.others') as PluralForms,
    },
    editor: {
      titleNew: t('faq.editor.titleNew'),
      titleEdit: t('faq.editor.titleEdit'),
      intro: t('faq.editor.intro'),
      failed: t('faq.editor.failed'),
      kept: t('faq.editor.kept'),
      question: {
        label: t('faq.editor.question.label'),
        hint: template('editor.question.hint'),
      },
      answer: {
        label: t('faq.editor.answer.label'),
        hint: template('editor.answer.hint'),
      },
    },
    errors: {
      questionMissing: t('faq.errors.questionMissing'),
      answerMissing: t('faq.errors.answerMissing'),
      questionTooLong: t.raw('faq.errors.questionTooLong') as PluralForms,
      answerTooLong: t.raw('faq.errors.answerTooLong') as PluralForms,
    },
  };
}

export function prelaunchCopyFrom(t: EditorTranslator): PrelaunchCopy {
  const basics = basicsCopyFrom(t);

  return {
    frame: basics.frame,
    errors: basics.errors,
    cover: basics.cover,
    loading: t('prelaunch.loading'),
    saveFailed: {
      title: t('prelaunch.saveFailed.title'),
      kept: t('prelaunch.saveFailed.kept'),
    },
    notOpen: {
      heading: t('prelaunch.notOpen.heading'),
      body: t('prelaunch.notOpen.body'),
      permanent: t('prelaunch.notOpen.permanent'),
      failed: t('prelaunch.notOpen.failed'),
      action: t('prelaunch.notOpen.action'),
    },
    live: {
      heading: t('prelaunch.live.heading'),
      countUnavailable: t('prelaunch.live.countUnavailable'),
      waiting: t.raw('prelaunch.live.waiting') as PluralForms,
      linkLabel: t('prelaunch.live.linkLabel'),
      linkHint: t('prelaunch.live.linkHint'),
      copy: t('prelaunch.live.copy'),
      copied: t('prelaunch.live.copied'),
      copiedAnnounced: t('prelaunch.live.copiedAnnounced'),
    },
    closed: { title: t('prelaunch.closed.title'), body: t('prelaunch.closed.body') },
    form: {
      heading: t('prelaunch.form.heading'),
      intro: t('prelaunch.form.intro'),
      /*
       * The two labels are the basics tab's own keys. The field is the same field — a
       * pre-launch page shows the campaign's title, not a second one — and two keys would be
       * two chances for the same control to be called different things on two tabs.
       */
      titleLabel: basics.title.label,
      titleHint: String(t.raw('prelaunch.form.titleHint')),
      blurbLabel: basics.blurb.label,
      blurbHint: String(t.raw('prelaunch.form.blurbHint')),
    },
    confirm: {
      title: t('prelaunch.confirm.title'),
      intro: t('prelaunch.confirm.intro'),
      body: t('prelaunch.confirm.body'),
      cancel: t('prelaunch.confirm.cancel'),
      action: t('prelaunch.confirm.action'),
      opening: t('prelaunch.confirm.opening'),
    },
  };
}

export function reviewCopyFrom(t: EditorTranslator): ReviewCopy {
  const frame = editorFrameCopyFrom(t);
  const template = (key: string): string => String(t.raw(`review.${key}`));

  return {
    frame,
    loading: t('review.loading'),
    loadFailed: t('review.loadFailed'),
    moderation: {
      rejected: t('review.moderation.rejected'),
      changes: t('review.moderation.changes'),
      noReason: t('review.moderation.noReason'),
    },
    stateNote: {
      SUBMITTED: t('review.stateNote.SUBMITTED'),
      APPROVED: t('review.stateNote.APPROVED'),
      SCHEDULED: t('review.stateNote.SCHEDULED'),
      REJECTED: t('review.stateNote.REJECTED'),
      LIVE: t('review.stateNote.LIVE'),
    },
    refusal: {
      title: t('review.refusal.title'),
      item: template('refusal.item'),
      plans: t('review.refusal.plans'),
      checkAgain: t('review.refusal.checkAgain'),
    },
    progress: {
      heading: t('review.progress.heading'),
      summary: template('progress.summary'),
      barLabel: template('progress.barLabel'),
    },
    blocking: {
      heading: t('review.blocking.heading'),
      description: t('review.blocking.description'),
    },
    advisory: {
      heading: t('review.advisory.heading'),
      description: t('review.advisory.description'),
    },
    row: {
      done: t('review.row.done'),
      requiredNotDone: t('review.row.requiredNotDone'),
      recommendedNotDone: t('review.row.recommendedNotDone'),
      status: template('row.status'),
      fix: template('row.fix'),
      fixDetail: template('row.fixDetail'),
    },
    submit: {
      heading: t('review.submit.heading'),
      submitting: t('review.submit.submitting'),
      ready: t('review.submit.ready'),
      held: t.raw('review.submit.held') as PluralForms,
      heldWithSuggestions: t.raw('review.submit.heldWithSuggestions') as PluralForms,
    },
    launch: {
      heading: t('review.launch.heading'),
      failed: t('review.launch.failed'),
      confirmHeading: t('review.launch.confirmHeading'),
      confirmBody: t('review.launch.confirmBody'),
      now: t('review.launch.now'),
      launching: t('review.launch.launching'),
      cancel: t('review.launch.cancel'),
      explanation: t('review.launch.explanation'),
    },
    failures: {
      notFound: t('review.failures.notFound'),
      planLimit: t('review.failures.planLimit'),
      forbidden: t('review.failures.forbidden'),
      refused: t('review.failures.refused'),
      unreachable: t('review.failures.unreachable'),
    },
    sections: {
      basics: frame.tabs.basics,
      rewards: frame.tabs.rewards,
      story: frame.tabs.story,
    },
  };
}
