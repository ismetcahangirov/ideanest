import type { AuditActionLabels } from '../../admin/audit';
import type { AdminTranslator } from '../admin-copy';
import type { ConsoleChromeCopy } from './common-copy';

/**
 * The words the console's platform screens draw — issue #324, epic #259.
 *
 * The rail's fifth group: the audit trail, the platform figures, the email templates, the
 * feature flags and the health board. Grouped as `CONSOLE_GROUPS` groups them so that the
 * file somebody opens to change a word is the one the rail would have sent them to.
 */

/**
 * AD-14, the audit trail.
 *
 * <h2>The two tables are open, and the fallback is the feature</h2>
 *
 * `entity` and `action` are looked up by the service's own wire spelling — `project.approved`,
 * `account.suspended` — and neither is exhaustive on purpose. The service's set of privileged
 * actions grows with every feature that adds one, and a screen that drew nothing for an action
 * it had not been taught would hide exactly the row somebody is looking for. An unknown action
 * falls back to its wire spelling, which is readable, and an unknown entity kind falls back to
 * the same.
 *
 * <p>That is also why they are open records rather than a key per known action: the
 * catalogue's set and the service's set are allowed to disagree, and the disagreement has to
 * fail soft in one direction only.
 *
 * <p>`action` is two levels deep where `entity` is one, because next-intl reads a `.` in a
 * message key as nesting and refuses a key that carries one. `project.approved` is therefore
 * `project` then `approved` in the catalogue, and `actionLabel` splits the wire spelling at
 * its first `.` to find it.
 */
export interface AuditTrailCopy extends ConsoleChromeCopy {
  /** What this screen calls the thing it reads, for the refusals. Already inflected. */
  readonly subject: string;
  readonly heading: string;
  readonly filterLabel: string;
  readonly everything: string;
  readonly loadingList: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  readonly filteredTitle: string;
  readonly filteredBody: string;
  readonly refused: string;
  /** Carries `{id}`. */
  readonly requestId: string;
  readonly footnote: string;
  /** Keyed by the entity kind the service writes. Falls back to the wire spelling. */
  readonly entity: Readonly<Record<string, string>>;
  /** Keyed by the action the service writes, split at its `.`. Falls back to the wire spelling. */
  readonly action: AuditActionLabels;
}

export function auditTrailCopyFrom(t: AdminTranslator, chrome: ConsoleChromeCopy): AuditTrailCopy {
  return {
    ...chrome,
    subject: t('screens.audit.subject'),
    heading: t('screens.audit.heading'),
    filterLabel: t('screens.audit.filterLabel'),
    everything: t('screens.audit.everything'),
    loadingList: t('screens.audit.loadingList'),
    emptyTitle: t('screens.audit.emptyTitle'),
    emptyBody: t('screens.audit.emptyBody'),
    filteredTitle: t('screens.audit.filteredTitle'),
    filteredBody: t('screens.audit.filteredBody'),
    refused: t('screens.audit.refused'),
    requestId: String(t.raw('screens.audit.requestId')),
    footnote: t('screens.audit.footnote'),
    entity: t.raw('screens.audit.entity') as Readonly<Record<string, string>>,
    action: t.raw('screens.audit.action') as AuditActionLabels,
  };
}

/**
 * AD-16, the health dashboard.
 *
 * <h2>Two sentences for one measurement, and not a unit bolted onto a number</h2>
 *
 * `measuredSeconds` and `measuredMinutes` are separate messages rather than one sentence with
 * a unit interpolated into it. The unit is a word that inflects: Russian says "секунд" beside
 * one number and "секунды" beside another, and Azerbaijani puts the whole phrase in a
 * different place than English does. A sentence assembled from a number, a space and a noun
 * can express none of that, and the version of it that existed here before #324 was a template
 * literal in JSX.
 */
export interface HealthDashboardCopy extends ConsoleChromeCopy {
  readonly subject: string;
  readonly loadingList: string;
  readonly readFailed: string;
  readonly notMonitoredTitle: string;
  readonly notMonitoredBody: string;
  readonly overall: string;
  /** Carries `{count}`. */
  readonly measuredSeconds: string;
  /** Carries `{count}`. */
  readonly measuredMinutes: string;
  readonly measureAgain: string;
  readonly queues: string;
  /** Carries `{count}`. */
  readonly waiting: string;
  /** Carries `{count}`. Never added into {@link waiting} — see `lib/admin/health.ts`. */
  readonly givenUp: string;
  readonly jobs: string;
  /** Carries `{count}`. */
  readonly overdue: string;
  readonly onTime: string;
  /** Carries `{count}`. */
  readonly attempts: string;
  readonly providers: string;
  readonly notConfigured: string;
  /** Keyed by `HealthStatus`. The word, never only the colour — CLAUDE.md §2. */
  readonly status: Readonly<Record<string, string>>;
  /**
   * The scheduler's own states, worded — issue #403.
   *
   * <p>`READY` was drawn raw on all nineteen rows, which is not information in any
   * language. The wire word moved to the row's `title`, so it is still one hover from
   * whoever is about to grep for it, and a state with no sentence is drawn as itself.
   */
  readonly jobState: Readonly<Record<string, string>>;
}

export function healthDashboardCopyFrom(
  t: AdminTranslator,
  chrome: ConsoleChromeCopy,
): HealthDashboardCopy {
  return {
    ...chrome,
    subject: t('screens.health.subject'),
    loadingList: t('screens.health.loadingList'),
    readFailed: t('screens.health.readFailed'),
    notMonitoredTitle: t('screens.health.notMonitoredTitle'),
    notMonitoredBody: t('screens.health.notMonitoredBody'),
    overall: t('screens.health.overall'),
    measuredSeconds: String(t.raw('screens.health.measuredSeconds')),
    measuredMinutes: String(t.raw('screens.health.measuredMinutes')),
    measureAgain: t('screens.health.measureAgain'),
    queues: t('screens.health.queues'),
    waiting: String(t.raw('screens.health.waiting')),
    givenUp: String(t.raw('screens.health.givenUp')),
    jobs: t('screens.health.jobs'),
    overdue: String(t.raw('screens.health.overdue')),
    onTime: t('screens.health.onTime'),
    attempts: String(t.raw('screens.health.attempts')),
    providers: t('screens.health.providers'),
    notConfigured: t('screens.health.notConfigured'),
    status: t.raw('screens.health.status') as Readonly<Record<string, string>>,
    jobState: t.raw('screens.health.jobState') as Readonly<Record<string, string>>,
  };
}

/** AD-12, the feature flags. */
export interface FlagConsoleCopy extends ConsoleChromeCopy {
  readonly subject: string;
  readonly heading: string;
  readonly loadingList: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  readonly on: string;
  readonly off: string;
  readonly rollout: string;
  /** Carries `{count}`. */
  readonly alwaysIn: string;
  /** Carries `{date}`. */
  readonly lastChanged: string;
  readonly offForEverybody: string;
  readonly addHeading: string;
  readonly addIntro: string;
  readonly nameLabel: string;
  readonly nameHint: string;
  readonly nameError: string;
  readonly switchesLabel: string;
  readonly working: string;
  readonly add: string;
  readonly failedTitle: string;
}

export function flagConsoleCopyFrom(
  t: AdminTranslator,
  chrome: ConsoleChromeCopy,
): FlagConsoleCopy {
  return {
    ...chrome,
    subject: t('screens.flags.subject'),
    heading: t('screens.flags.heading'),
    loadingList: t('screens.flags.loadingList'),
    emptyTitle: t('screens.flags.emptyTitle'),
    emptyBody: t('screens.flags.emptyBody'),
    on: t('screens.flags.on'),
    off: t('screens.flags.off'),
    rollout: t('screens.flags.rollout'),
    alwaysIn: String(t.raw('screens.flags.alwaysIn')),
    lastChanged: String(t.raw('screens.flags.lastChanged')),
    offForEverybody: t('screens.flags.offForEverybody'),
    addHeading: t('screens.flags.addHeading'),
    addIntro: t('screens.flags.addIntro'),
    nameLabel: t('screens.flags.nameLabel'),
    nameHint: t('screens.flags.nameHint'),
    nameError: t('screens.flags.nameError'),
    switchesLabel: t('screens.flags.switchesLabel'),
    working: t('screens.flags.working'),
    add: t('screens.flags.add'),
    failedTitle: t('screens.flags.failedTitle'),
  };
}

/**
 * AD-13, the platform's own figures.
 *
 * <p>`windows` is keyed by the number of days rather than holding a formatted string per
 * option, because "30 days" is a noun phrase that inflects and three of them written as
 * `${n} days` in JSX is three sentences no translation can reach. The keys are the values the
 * screen already offers.
 *
 * <p>What the screen does <em>not</em> answer — cohorts and funnels — is named by the service
 * and worded here, since #403. It sent the sentences themselves until then, in English, under
 * a translated heading, which made them the only untranslated paragraph on the screen.
 *
 * <p>Codes keep the property the arrangement existed for: the day cohorts are built the
 * service stops sending `COHORTS` and the panel disappears without a frontend change. A code
 * this table has no sentence for is drawn as itself, so a new one shows up in English rather
 * than vanishing.
 */
export interface PlatformAnalyticsCopy extends ConsoleChromeCopy {
  readonly subject: string;
  readonly loadingList: string;
  readonly windows: Readonly<Record<string, string>>;
  /** Carries `{from}`, `{to}`. */
  readonly range: string;
  readonly volume: string;
  readonly pledges: string;
  readonly backers: string;
  readonly averagePledge: string;
  readonly liveProjects: string;
  readonly otherCurrencyTitle: string;
  /** Carries `{count}`. */
  readonly otherCurrencyBody: string;
  readonly outcomesHeading: string;
  readonly noneClosed: string;
  readonly succeeded: string;
  readonly didNot: string;
  readonly successRate: string;
  readonly dailyHeading: string;
  readonly nothingPledged: string;
  readonly notBuiltHeading: string;
  /** Keyed by the code the service sends. Unknown codes fall through to themselves. */
  readonly notBuilt: Readonly<Record<string, string>>;
}

export function platformAnalyticsCopyFrom(
  t: AdminTranslator,
  chrome: ConsoleChromeCopy,
): PlatformAnalyticsCopy {
  return {
    ...chrome,
    subject: t('screens.analytics.subject'),
    loadingList: t('screens.analytics.loadingList'),
    windows: t.raw('screens.analytics.windows') as Readonly<Record<string, string>>,
    range: String(t.raw('screens.analytics.range')),
    volume: t('screens.analytics.volume'),
    pledges: t('screens.analytics.pledges'),
    backers: t('screens.analytics.backers'),
    averagePledge: t('screens.analytics.averagePledge'),
    liveProjects: t('screens.analytics.liveProjects'),
    otherCurrencyTitle: t('screens.analytics.otherCurrencyTitle'),
    otherCurrencyBody: String(t.raw('screens.analytics.otherCurrencyBody')),
    outcomesHeading: t('screens.analytics.outcomesHeading'),
    noneClosed: t('screens.analytics.noneClosed'),
    succeeded: t('screens.analytics.succeeded'),
    didNot: t('screens.analytics.didNot'),
    successRate: t('screens.analytics.successRate'),
    dailyHeading: t('screens.analytics.dailyHeading'),
    nothingPledged: t('screens.analytics.nothingPledged'),
    notBuiltHeading: t('screens.analytics.notBuiltHeading'),
    notBuilt: t.raw('screens.analytics.notBuilt') as Readonly<Record<string, string>>,
  };
}

/**
 * AD-15's list.
 *
 * <p>`category` is the console's own table rather than `account.notifications.category`, which
 * is written for the person receiving the notice â€” "Your pledges", "Campaigns you back". A
 * member of staff reading a list of templates is not the recipient, and second-person copy on
 * an administrative list reads as though the console belonged to somebody else. Unknown
 * categories fall back to the wire value.
 */
export interface EmailTemplateIndexCopy extends ConsoleChromeCopy {
  readonly subject: string;
  readonly loadingList: string;
  readonly readFailed: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  readonly transactional: string;
  readonly category: Readonly<Record<string, string>>;
}

export function emailTemplateIndexCopyFrom(
  t: AdminTranslator,
  chrome: ConsoleChromeCopy,
): EmailTemplateIndexCopy {
  return {
    ...chrome,
    subject: t('screens.emailTemplates.subject'),
    loadingList: t('screens.emailTemplates.loadingList'),
    readFailed: t('screens.emailTemplates.readFailed'),
    emptyTitle: t('screens.emailTemplates.emptyTitle'),
    emptyBody: t('screens.emailTemplates.emptyBody'),
    transactional: t('screens.emailTemplates.transactional'),
    category: t.raw('screens.emailTemplates.category') as Readonly<Record<string, string>>,
  };
}

/**
 * AD-15's editor.
 *
 * <p>`historySubject` is a second subject for the same screen: it reads two things, and a
 * refusal about the version history that said "the email copy" would send somebody looking at
 * the wrong endpoint.
 *
 * <p>`mustKeep` and `mustKeepWhy` bracket a list of `{n}` placeholders the component renders as
 * `<code>` elements. Two keys rather than one because the middle is markup, and the alternative
 * â€” a single sentence with the codes interpolated as text â€” would lose the styling that tells
 * an editor these are literals rather than words.
 */
export interface EmailTemplateEditorCopy extends ConsoleChromeCopy {
  readonly subject: string;
  readonly historySubject: string;
  readonly loadingList: string;
  readonly readFailed: string;
  readonly overriddenTitle: string;
  /** Carries `{version}`. */
  readonly overriddenBody: string;
  readonly shippedTitle: string;
  readonly shippedBody: string;
  readonly shippedHeading: string;
  readonly yourHeading: string;
  readonly mustKeep: string;
  readonly mustKeepWhy: string;
  readonly subjectLabel: string;
  readonly bodyLabel: string;
  readonly whyLabel: string;
  readonly whyHint: string;
  readonly missingTitle: string;
  /** Carries `{placeholders}`. */
  readonly missingBody: string;
  readonly saving: string;
  readonly saveNew: string;
  readonly withdraw: string;
  readonly savedNew: string;
  readonly savedWithdrawn: string;
  readonly doneTitle: string;
  readonly failedTitle: string;
  readonly versionsHeading: string;
  /** Carries `{date}`, `{version}`. */
  readonly versionLine: string;
  readonly live: string;
}

export function emailTemplateEditorCopyFrom(
  t: AdminTranslator,
  chrome: ConsoleChromeCopy,
): EmailTemplateEditorCopy {
  return {
    ...chrome,
    subject: t('screens.emailTemplateEditor.subject'),
    historySubject: t('screens.emailTemplateEditor.historySubject'),
    loadingList: t('screens.emailTemplateEditor.loadingList'),
    readFailed: t('screens.emailTemplateEditor.readFailed'),
    overriddenTitle: t('screens.emailTemplateEditor.overriddenTitle'),
    overriddenBody: String(t.raw('screens.emailTemplateEditor.overriddenBody')),
    shippedTitle: t('screens.emailTemplateEditor.shippedTitle'),
    shippedBody: t('screens.emailTemplateEditor.shippedBody'),
    shippedHeading: t('screens.emailTemplateEditor.shippedHeading'),
    yourHeading: t('screens.emailTemplateEditor.yourHeading'),
    mustKeep: t('screens.emailTemplateEditor.mustKeep'),
    mustKeepWhy: t('screens.emailTemplateEditor.mustKeepWhy'),
    subjectLabel: t('screens.emailTemplateEditor.subjectLabel'),
    bodyLabel: t('screens.emailTemplateEditor.bodyLabel'),
    whyLabel: t('screens.emailTemplateEditor.whyLabel'),
    whyHint: t('screens.emailTemplateEditor.whyHint'),
    missingTitle: t('screens.emailTemplateEditor.missingTitle'),
    missingBody: String(t.raw('screens.emailTemplateEditor.missingBody')),
    saving: t('screens.emailTemplateEditor.saving'),
    saveNew: t('screens.emailTemplateEditor.saveNew'),
    withdraw: t('screens.emailTemplateEditor.withdraw'),
    savedNew: t('screens.emailTemplateEditor.savedNew'),
    savedWithdrawn: t('screens.emailTemplateEditor.savedWithdrawn'),
    doneTitle: t('screens.emailTemplateEditor.doneTitle'),
    failedTitle: t('screens.emailTemplateEditor.failedTitle'),
    versionsHeading: t('screens.emailTemplateEditor.versionsHeading'),
    versionLine: String(t.raw('screens.emailTemplateEditor.versionLine')),
    live: t('screens.emailTemplateEditor.live'),
  };
}
