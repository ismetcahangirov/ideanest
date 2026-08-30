import type { AdminTranslator } from '../admin-copy';
import type { PluralForms } from '../plurals';
import type { CollectionKind } from '../../admin/curation';
import type { ConsoleChromeCopy } from './common-copy';

/**
 * The words the console's curation screens draw — issue #324, epic #259.
 *
 * The rail's second group: the collection manager, one collection's editor, the editorial
 * badges, the open calls, the placement order and the taxonomy.
 *
 * <h2>Six screens over one table, and one shared vocabulary</h2>
 *
 * `lib/admin/curation.ts` records why four of these are four questions about the same rows:
 * a staff selection, a themed list and an open call have the same slug, copy, publication
 * decision, window and membership behind them, and V14 put all three in one table on purpose.
 * The consequence here is that the same four words — the kind, whether it is published,
 * whether it badges, and the two move controls — appear on every one of them, so they are
 * {@link CurationCopy} rather than four copies of one table.
 */

/** What every curation screen says about a collection, whatever else it says. */
export interface CurationCopy {
  /** Keyed by `CollectionKind`. */
  readonly kind: Readonly<Record<CollectionKind, string>>;
  readonly published: string;
  readonly unpublished: string;
  readonly grantsBadge: string;
  readonly moveUp: string;
  readonly moveDown: string;
  /** Carries `{title}`. The visible word is inside it, for WCAG 2.5.3. */
  readonly moveUpLabel: string;
  /** Carries `{title}`. */
  readonly moveDownLabel: string;
  /**
   * A busy label, and not `common.saving`.
   *
   * The shared one is "Saving…" with an ellipsis, which reads as a status line. These are
   * button labels, where an ellipsis conventionally means "this opens something".
   */
  readonly saving: string;
}

/**
 * The chrome every curation screen carries.
 *
 * A second layer over {@link ConsoleChromeCopy} rather than a field on each screen, so that a
 * screen's builder spreads one object and cannot forget the half of it that is shared.
 */
export interface CurationChromeCopy extends ConsoleChromeCopy {
  readonly curation: CurationCopy;
}

export function curationCopyFrom(t: AdminTranslator): CurationCopy {
  return {
    kind: t.raw('curation.kind') as Readonly<Record<CollectionKind, string>>,
    published: t('curation.published'),
    unpublished: t('curation.unpublished'),
    grantsBadge: t('curation.grantsBadge'),
    moveUp: t('curation.moveUp'),
    moveDown: t('curation.moveDown'),
    moveUpLabel: String(t.raw('curation.moveUpLabel')),
    moveDownLabel: String(t.raw('curation.moveDownLabel')),
    saving: t('curation.saving'),
  };
}

export function curationChromeFrom(
  t: AdminTranslator,
  chrome: ConsoleChromeCopy,
): CurationChromeCopy {
  return { ...chrome, curation: curationCopyFrom(t) };
}

/**
 * AD-03's manager, and the three narrowed screens that are the same component.
 *
 * <p>`emptyTitle` and `emptyBody` are this screen's own. The three narrowed screens pass their
 * own pair as props, because "no collections yet" is wrong on a page that is asking a narrower
 * question and has found nothing.
 */
export interface CollectionManagerCopy extends CurationChromeCopy {
  readonly subject: string;
  readonly heading: string;
  readonly loadingList: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  readonly newHeading: string;
  readonly newIntro: string;
  readonly handleLabel: string;
  readonly handleHint: string;
  readonly titleLabel: string;
  readonly titleHint: string;
  readonly kindLabel: string;
  readonly create: string;
  readonly creating: string;
  /** Carries `{title}`. */
  readonly createdNotice: string;
  /** Carries `{title}`. */
  readonly publishedNotice: string;
  /** Carries `{title}`. */
  readonly unpublishedNotice: string;
  readonly publish: string;
  readonly takeDown: string;
  /** Carries `{order}`. */
  readonly placement: string;
  /** Carries `{title}`. */
  readonly publishTitle: string;
  /** Carries `{title}`. */
  readonly unpublishTitle: string;
  readonly publishDescription: string;
  readonly unpublishDescription: string;
  readonly publishBadgeBody: string;
  readonly publishing: string;
  readonly takingDown: string;
}

export function collectionManagerCopyFrom(
  t: AdminTranslator,
  chrome: CurationChromeCopy,
): CollectionManagerCopy {
  return {
    ...chrome,
    subject: t('screens.collections.subject'),
    heading: t('screens.collections.heading'),
    loadingList: t('screens.collections.loadingList'),
    emptyTitle: t('screens.collections.emptyTitle'),
    emptyBody: t('screens.collections.emptyBody'),
    newHeading: t('screens.collections.newHeading'),
    newIntro: t('screens.collections.newIntro'),
    handleLabel: t('screens.collections.handleLabel'),
    handleHint: t('screens.collections.handleHint'),
    titleLabel: t('screens.collections.titleLabel'),
    titleHint: t('screens.collections.titleHint'),
    kindLabel: t('screens.collections.kindLabel'),
    create: t('screens.collections.create'),
    creating: t('screens.collections.creating'),
    createdNotice: String(t.raw('screens.collections.createdNotice')),
    publishedNotice: String(t.raw('screens.collections.publishedNotice')),
    unpublishedNotice: String(t.raw('screens.collections.unpublishedNotice')),
    publish: t('screens.collections.publish'),
    takeDown: t('screens.collections.takeDown'),
    placement: String(t.raw('screens.collections.placement')),
    publishTitle: String(t.raw('screens.collections.publishTitle')),
    unpublishTitle: String(t.raw('screens.collections.unpublishTitle')),
    publishDescription: t('screens.collections.publishDescription'),
    unpublishDescription: t('screens.collections.unpublishDescription'),
    publishBadgeBody: t('screens.collections.publishBadgeBody'),
    publishing: t('screens.collections.publishing'),
    takingDown: t('screens.collections.takingDown'),
  };
}

/**
 * One collection, and the campaigns in it.
 *
 * <p>`hidden` declines rather than branching on one-or-more, and its `one` form deliberately
 * writes the word out â€” "One campaign here is" â€” because that is the sentence the screen
 * carried before #324 and a count is not what makes it readable.
 */
export interface CollectionEditorCopy extends CurationChromeCopy {
  readonly subject: string;
  readonly loadingList: string;
  readonly notFoundTitle: string;
  readonly notFoundBody: string;
  readonly backToCollections: string;
  readonly placement: string;
  readonly languages: string;
  readonly noLanguages: string;
  readonly campaigns: string;
  /** Carries `{count}` in every form but `one`, which writes the word out. */
  readonly hidden: PluralForms;
  readonly addLabel: string;
  readonly addHint: string;
  readonly add: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  readonly remove: string;
  /** Carries `{title}`. */
  readonly removeLabel: string;
  readonly addedNotice: string;
  /** Carries `{title}`. */
  readonly removedNotice: string;
  readonly addTitle: string;
  /** Carries `{title}`. */
  readonly removeTitle: string;
  readonly addDescription: string;
  readonly removeDescription: string;
  readonly addBadgeBody: string;
  readonly removeBadgeBody: string;
  readonly adding: string;
  readonly removing: string;
}

export function collectionEditorCopyFrom(
  t: AdminTranslator,
  chrome: CurationChromeCopy,
): CollectionEditorCopy {
  return {
    ...chrome,
    subject: t('screens.collectionEditor.subject'),
    loadingList: t('screens.collectionEditor.loadingList'),
    notFoundTitle: t('screens.collectionEditor.notFoundTitle'),
    notFoundBody: t('screens.collectionEditor.notFoundBody'),
    backToCollections: t('screens.collectionEditor.backToCollections'),
    placement: t('screens.collectionEditor.placement'),
    languages: t('screens.collectionEditor.languages'),
    noLanguages: t('screens.collectionEditor.noLanguages'),
    campaigns: t('screens.collectionEditor.campaigns'),
    hidden: t.raw('screens.collectionEditor.hidden') as PluralForms,
    addLabel: t('screens.collectionEditor.addLabel'),
    addHint: t('screens.collectionEditor.addHint'),
    add: t('screens.collectionEditor.add'),
    emptyTitle: t('screens.collectionEditor.emptyTitle'),
    emptyBody: t('screens.collectionEditor.emptyBody'),
    remove: t('screens.collectionEditor.remove'),
    removeLabel: String(t.raw('screens.collectionEditor.removeLabel')),
    addedNotice: t('screens.collectionEditor.addedNotice'),
    removedNotice: String(t.raw('screens.collectionEditor.removedNotice')),
    addTitle: t('screens.collectionEditor.addTitle'),
    removeTitle: String(t.raw('screens.collectionEditor.removeTitle')),
    addDescription: t('screens.collectionEditor.addDescription'),
    removeDescription: t('screens.collectionEditor.removeDescription'),
    addBadgeBody: t('screens.collectionEditor.addBadgeBody'),
    removeBadgeBody: t('screens.collectionEditor.removeBadgeBody'),
    adding: t('screens.collectionEditor.adding'),
    removing: t('screens.collectionEditor.removing'),
  };
}

/**
 * AD-03's badge manager.
 *
 * <p>Two headings and two verbs, because the screen is one list split in two and the split is
 * the point: a staff selection is the usual carrier of Â§3.2's badge and does not imply it.
 */
export interface BadgeManagerCopy extends CurationChromeCopy {
  readonly subject: string;
  readonly heading: string;
  readonly loadingList: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  readonly restHeading: string;
  readonly restIntro: string;
  readonly allGrant: string;
  readonly unpublishedBadgesNothing: string;
  readonly grant: string;
  readonly stopGranting: string;
  /** Carries `{title}`. */
  readonly grantedNotice: string;
  /** Carries `{title}`. */
  readonly stoppedNotice: string;
  /** Carries `{title}`. */
  readonly grantTitle: string;
  /** Carries `{title}`. */
  readonly stopTitle: string;
  readonly grantDescription: string;
  readonly stopDescription: string;
  readonly dialogBody: string;
  readonly grantConfirm: string;
  readonly stopConfirm: string;
}

export function badgeManagerCopyFrom(
  t: AdminTranslator,
  chrome: CurationChromeCopy,
): BadgeManagerCopy {
  return {
    ...chrome,
    subject: t('screens.badges.subject'),
    heading: t('screens.badges.heading'),
    loadingList: t('screens.badges.loadingList'),
    emptyTitle: t('screens.badges.emptyTitle'),
    emptyBody: t('screens.badges.emptyBody'),
    restHeading: t('screens.badges.restHeading'),
    restIntro: t('screens.badges.restIntro'),
    allGrant: t('screens.badges.allGrant'),
    unpublishedBadgesNothing: t('screens.badges.unpublishedBadgesNothing'),
    grant: t('screens.badges.grant'),
    stopGranting: t('screens.badges.stopGranting'),
    grantedNotice: String(t.raw('screens.badges.grantedNotice')),
    stoppedNotice: String(t.raw('screens.badges.stoppedNotice')),
    grantTitle: String(t.raw('screens.badges.grantTitle')),
    stopTitle: String(t.raw('screens.badges.stopTitle')),
    grantDescription: t('screens.badges.grantDescription'),
    stopDescription: t('screens.badges.stopDescription'),
    dialogBody: t('screens.badges.dialogBody'),
    grantConfirm: t('screens.badges.grantConfirm'),
    stopConfirm: t('screens.badges.stopConfirm'),
  };
}

/**
 * AD-03's programmes, and the windows that decide whether they answer at all.
 *
 * <p>`savedNotice` says "The window for {title} was saved" rather than "{title}'s window was
 * saved", which is what the screen said before #324. The possessive was built by putting an
 * apostrophe and an s after a value, and that is English grammar applied to a name â€” every
 * other language in the catalogue marks possession somewhere else in the sentence.
 */
export interface OpenCallManagerCopy extends CurationChromeCopy {
  readonly subject: string;
  readonly heading: string;
  readonly loadingList: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  /** Carries `{title}`. */
  readonly savedNotice: string;
  readonly open: string;
  readonly notYet: string;
  readonly closed: string;
  readonly outsideWindow: string;
  readonly opensLabel: string;
  readonly opensHint: string;
  readonly closesLabel: string;
  readonly closesHint: string;
  readonly saveWindow: string;
  readonly utcNote: string;
}

export function openCallManagerCopyFrom(
  t: AdminTranslator,
  chrome: CurationChromeCopy,
): OpenCallManagerCopy {
  return {
    ...chrome,
    subject: t('screens.openCalls.subject'),
    heading: t('screens.openCalls.heading'),
    loadingList: t('screens.openCalls.loadingList'),
    emptyTitle: t('screens.openCalls.emptyTitle'),
    emptyBody: t('screens.openCalls.emptyBody'),
    savedNotice: String(t.raw('screens.openCalls.savedNotice')),
    open: t('screens.openCalls.open'),
    notYet: t('screens.openCalls.notYet'),
    closed: t('screens.openCalls.closed'),
    outsideWindow: t('screens.openCalls.outsideWindow'),
    opensLabel: t('screens.openCalls.opensLabel'),
    opensHint: t('screens.openCalls.opensHint'),
    closesLabel: t('screens.openCalls.closesLabel'),
    closesHint: t('screens.openCalls.closesHint'),
    saveWindow: t('screens.openCalls.saveWindow'),
    utcNote: t('screens.openCalls.utcNote'),
  };
}

/**
 * AD-03's placement order.
 *
 * <p>`partial` is appended to a refusal rather than being one: a move is two requests, and the
 * sentence that matters after a failure is which of them landed.
 */
export interface PlacementEditorCopy extends CurationChromeCopy {
  readonly subject: string;
  readonly heading: string;
  readonly intro: string;
  readonly errorTitle: string;
  readonly loadingList: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  /** Carries `{title}`. */
  readonly movedUp: string;
  /** Carries `{title}`. */
  readonly movedDown: string;
  readonly partial: string;
}

export function placementEditorCopyFrom(
  t: AdminTranslator,
  chrome: CurationChromeCopy,
): PlacementEditorCopy {
  return {
    ...chrome,
    subject: t('screens.placements.subject'),
    heading: t('screens.placements.heading'),
    intro: t('screens.placements.intro'),
    errorTitle: t('screens.placements.errorTitle'),
    loadingList: t('screens.placements.loadingList'),
    emptyTitle: t('screens.placements.emptyTitle'),
    emptyBody: t('screens.placements.emptyBody'),
    movedUp: String(t.raw('screens.placements.movedUp')),
    movedDown: String(t.raw('screens.placements.movedDown')),
    partial: t('screens.placements.partial'),
  };
}

/**
 * AD-08, and the two things it will not do.
 *
 * <p>`tagsSubject` is a second subject for the same screen â€” it reads the tree and the tag
 * list from two endpoints, and a refusal about one that named the other would send somebody to
 * the wrong place.
 */
export interface TaxonomyManagerCopy extends CurationChromeCopy {
  readonly subject: string;
  readonly tagsSubject: string;
  readonly warningTitle: string;
  readonly warningBody: string;
  readonly categories: string;
  readonly loadingList: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  readonly failedTitle: string;
  readonly addCategoryHeading: string;
  readonly handleLabel: string;
  readonly handleHint: string;
  readonly azLabel: string;
  readonly enLabel: string;
  readonly add: string;
  readonly edit: string;
  readonly done: string;
  readonly noSubcategories: string;
  /** Carries `{count}`. */
  readonly subcategories: PluralForms;
  readonly rename: string;
  readonly localeLabel: string;
  readonly translationLabel: string;
  readonly translate: string;
  readonly newSubcategoryLabel: string;
  readonly newSubcategoryHint: string;
  readonly tagsHeading: string;
  readonly tagsIntro: string;
  readonly noTags: string;
}

export function taxonomyManagerCopyFrom(
  t: AdminTranslator,
  chrome: CurationChromeCopy,
): TaxonomyManagerCopy {
  return {
    ...chrome,
    subject: t('screens.taxonomy.subject'),
    tagsSubject: t('screens.taxonomy.tagsSubject'),
    warningTitle: t('screens.taxonomy.warningTitle'),
    warningBody: t('screens.taxonomy.warningBody'),
    categories: t('screens.taxonomy.categories'),
    loadingList: t('screens.taxonomy.loadingList'),
    emptyTitle: t('screens.taxonomy.emptyTitle'),
    emptyBody: t('screens.taxonomy.emptyBody'),
    failedTitle: t('screens.taxonomy.failedTitle'),
    addCategoryHeading: t('screens.taxonomy.addCategoryHeading'),
    handleLabel: t('screens.taxonomy.handleLabel'),
    handleHint: t('screens.taxonomy.handleHint'),
    azLabel: t('screens.taxonomy.azLabel'),
    enLabel: t('screens.taxonomy.enLabel'),
    add: t('screens.taxonomy.add'),
    edit: t('screens.taxonomy.edit'),
    done: t('screens.taxonomy.done'),
    noSubcategories: t('screens.taxonomy.noSubcategories'),
    subcategories: t.raw('screens.taxonomy.subcategories') as PluralForms,
    rename: t('screens.taxonomy.rename'),
    localeLabel: t('screens.taxonomy.localeLabel'),
    translationLabel: t('screens.taxonomy.translationLabel'),
    translate: t('screens.taxonomy.translate'),
    newSubcategoryLabel: t('screens.taxonomy.newSubcategoryLabel'),
    newSubcategoryHint: t('screens.taxonomy.newSubcategoryHint'),
    tagsHeading: t('screens.taxonomy.tagsHeading'),
    tagsIntro: t('screens.taxonomy.tagsIntro'),
    noTags: t('screens.taxonomy.noTags'),
  };
}
