import type { StaffCapability, StaffRole } from '../../admin/staff';
import type { TicketPriority, TicketState } from '../../admin/tickets';
import type { AdminTranslator } from '../admin-copy';
import type { PluralForms } from '../plurals';
import type { ConsoleChromeCopy } from './common-copy';

/**
 * The words the console's people screens draw — issue #324, epic #259.
 *
 * The rail's third group: the account directory, the support queue and the staff roster.
 *
 * <h2>Two of these keep refusals of their own</h2>
 *
 * `UserDirectory` and `StaffRoles` say something the shared table does not — one names the
 * directory and what searching it costs, the other explains that reading the roster needs a
 * capability the reader has not got. Both said that before #324, and translating a surface is
 * not the change that gets to reword it.
 *
 * <h2>The wire words that stay</h2>
 *
 * A capability is rendered as its own identifier — `ISSUE_REFUND` — with the translated
 * sentence on its `title`. That is deliberate: the identifier is what a member of staff asks an
 * administrator for and what the service's refusal names back at them, so a translated chip
 * would be a word nothing else in the system answers to. The roles, which are four and are read
 * as ordinary nouns, are translated.
 */

/**
 * AD-04, the account directory.
 *
 * <p>The dates are the reader's own `toLocaleDateString`, which needs no key. What needed one
 * is the sentence around them: "joined {date}" and "at an unknown date" were a template literal
 * and a fallback string concatenated in JSX, and the two languages that put the verb last could
 * not express either.
 */
export interface UserDirectoryCopy extends ConsoleChromeCopy {
  /**
   * What the reader was trying to read, for the shared capability refusal — #400.
   *
   * <p>This screen keeps its own not-staff sentence, which names the directory and what
   * searching it costs. The other 403 is the console's, because "you need
   * `ADMINISTER_ACCOUNTS`" is the same sentence everywhere and the service supplies the
   * only part of it that differs.
   */
  readonly subject: string;
  readonly signedOutTitle: string;
  readonly signedOutBody: string;
  readonly forbiddenTitle: string;
  readonly forbiddenBody: string;
  readonly notStaff: string;
  readonly accountNotFound: string;
  readonly selfSuspend: string;
  readonly heading: string;
  readonly searchLabel: string;
  readonly searchHint: string;
  readonly search: string;
  readonly standing: string;
  readonly suspendedOnly: string;
  readonly loadingList: string;
  readonly filteredTitle: string;
  readonly filteredBody: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  /** Carries `{date}`. */
  readonly joined: string;
  readonly unknownDate: string;
  readonly emailVerified: string;
  readonly emailUnverified: string;
  readonly suspendedTag: string;
  readonly leaving: string;
  /** Carries `{date}`. */
  readonly suspendedOn: string;
  readonly reinstate: string;
  readonly reinstating: string;
  readonly suspend: string;
  /** Carries `{count}`. */
  readonly accountCount: PluralForms;
  /** Carries `{accounts}`. */
  readonly showing: string;
  readonly andMore: string;
  /** Carries `{name}`. */
  readonly suspendTitle: string;
  /** Carries `{email}`. */
  readonly suspendDescription: string;
  readonly suspendBody: string;
  readonly suspendAccount: string;
  readonly suspending: string;
  readonly reasonLabel: string;
  readonly reasonHint: string;
  readonly reasonRequired: string;
  /** Carries `{limit}`. */
  readonly reasonTooLong: string;
  /** Carries `{name}`. */
  readonly suspendedNotice: string;
  /** Carries `{name}`. */
  readonly reinstatedNotice: string;
}

export function userDirectoryCopyFrom(
  t: AdminTranslator,
  chrome: ConsoleChromeCopy,
): UserDirectoryCopy {
  return {
    ...chrome,
    subject: t('screens.users.subject'),
    signedOutTitle: t('screens.users.signedOutTitle'),
    signedOutBody: t('screens.users.signedOutBody'),
    forbiddenTitle: t('screens.users.forbiddenTitle'),
    forbiddenBody: t('screens.users.forbiddenBody'),
    notStaff: t('screens.users.notStaff'),
    accountNotFound: t('screens.users.accountNotFound'),
    selfSuspend: t('screens.users.selfSuspend'),
    heading: t('screens.users.heading'),
    searchLabel: t('screens.users.searchLabel'),
    searchHint: t('screens.users.searchHint'),
    search: t('screens.users.search'),
    standing: t('screens.users.standing'),
    suspendedOnly: t('screens.users.suspendedOnly'),
    loadingList: t('screens.users.loadingList'),
    filteredTitle: t('screens.users.filteredTitle'),
    filteredBody: t('screens.users.filteredBody'),
    emptyTitle: t('screens.users.emptyTitle'),
    emptyBody: t('screens.users.emptyBody'),
    joined: String(t.raw('screens.users.joined')),
    unknownDate: t('screens.users.unknownDate'),
    emailVerified: t('screens.users.emailVerified'),
    emailUnverified: t('screens.users.emailUnverified'),
    suspendedTag: t('screens.users.suspendedTag'),
    leaving: t('screens.users.leaving'),
    suspendedOn: String(t.raw('screens.users.suspendedOn')),
    reinstate: t('screens.users.reinstate'),
    reinstating: t('screens.users.reinstating'),
    suspend: t('screens.users.suspend'),
    accountCount: t.raw('screens.users.accountCount') as PluralForms,
    showing: String(t.raw('screens.users.showing')),
    andMore: t('screens.users.andMore'),
    suspendTitle: String(t.raw('screens.users.suspendTitle')),
    suspendDescription: String(t.raw('screens.users.suspendDescription')),
    suspendBody: t('screens.users.suspendBody'),
    suspendAccount: t('screens.users.suspendAccount'),
    suspending: t('screens.users.suspending'),
    reasonLabel: t('screens.users.reasonLabel'),
    reasonHint: t('screens.users.reasonHint'),
    reasonRequired: t('screens.users.reasonRequired'),
    reasonTooLong: String(t.raw('screens.users.reasonTooLong')),
    suspendedNotice: String(t.raw('screens.users.suspendedNotice')),
    reinstatedNotice: String(t.raw('screens.users.reinstatedNotice')),
  };
}

/**
 * AD-10, the support queue.
 *
 * <p>`priority` and `state` are translated where a ticket's other enum-ish values are not,
 * because these two are what a member of staff sorts and filters by rather than what they quote
 * back to an engineer. The `<option value>` beneath each stays the wire word.
 */
export interface SupportConsoleCopy extends ConsoleChromeCopy {
  readonly subject: string;
  readonly ticketSubject: string;
  readonly heading: string;
  readonly intro: string;
  readonly loadingList: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
  readonly unassigned: string;
  readonly loadingTicket: string;
  readonly ticketFailedTitle: string;
  readonly tryAgainShort: string;
  readonly staff: string;
  readonly requester: string;
  readonly internalNote: string;
  readonly replyLabel: string;
  readonly send: string;
  readonly internalLabel: string;
  readonly priorityLabel: string;
  readonly stateLabel: string;
  readonly putBack: string;
  /** Carries `{count}`. */
  readonly otherTickets: PluralForms;
  readonly failedTitle: string;
  readonly priority: Readonly<Record<TicketPriority, string>>;
  readonly state: Readonly<Record<TicketState, string>>;
}

export function supportConsoleCopyFrom(
  t: AdminTranslator,
  chrome: ConsoleChromeCopy,
): SupportConsoleCopy {
  return {
    ...chrome,
    subject: t('screens.support.subject'),
    ticketSubject: t('screens.support.ticketSubject'),
    heading: t('screens.support.heading'),
    intro: t('screens.support.intro'),
    loadingList: t('screens.support.loadingList'),
    emptyTitle: t('screens.support.emptyTitle'),
    emptyBody: t('screens.support.emptyBody'),
    unassigned: t('screens.support.unassigned'),
    loadingTicket: t('screens.support.loadingTicket'),
    ticketFailedTitle: t('screens.support.ticketFailedTitle'),
    tryAgainShort: t('screens.support.tryAgainShort'),
    staff: t('screens.support.staff'),
    requester: t('screens.support.requester'),
    internalNote: t('screens.support.internalNote'),
    replyLabel: t('screens.support.replyLabel'),
    send: t('screens.support.send'),
    internalLabel: t('screens.support.internalLabel'),
    priorityLabel: t('screens.support.priorityLabel'),
    stateLabel: t('screens.support.stateLabel'),
    putBack: t('screens.support.putBack'),
    otherTickets: t.raw('screens.support.otherTickets') as PluralForms,
    failedTitle: t('screens.support.failedTitle'),
    priority: t.raw('screens.support.priority') as Readonly<Record<TicketPriority, string>>,
    state: t.raw('screens.support.state') as Readonly<Record<TicketState, string>>,
  };
}

/**
 * The role model, as a screen.
 *
 * <p>`capability` is a table of sentences rather than of names: the name is drawn as itself and
 * this is what sits on its `title`. It moved out of `lib/admin/staff.ts`, where it was the one
 * English table in a module otherwise made of identifiers.
 */
export interface StaffRolesCopy extends ConsoleChromeCopy {
  readonly subject: string;
  readonly meSubject: string;
  readonly myHeading: string;
  readonly loadingMe: string;
  readonly notStaffTitle: string;
  readonly notStaffBody: string;
  /** Carries `{id}`, `{roles}`. */
  readonly signedInAs: string;
  readonly bootstrapTitle: string;
  /** Carries `{variable}`. */
  readonly bootstrapBody: string;
  readonly rosterHeading: string;
  readonly rosterForbiddenTitle: string;
  /** Carries `{capability}`. */
  readonly rosterForbiddenBody: string;
  readonly loadingRoster: string;
  readonly rosterEmptyTitle: string;
  readonly rosterEmptyBody: string;
  /** Carries `{by}`, `{date}`. */
  readonly grantedBy: string;
  readonly withdraw: string;
  readonly grantHeading: string;
  readonly grantIntro: string;
  readonly accountLabel: string;
  readonly accountHint: string;
  readonly roleLabel: string;
  readonly noteLabel: string;
  readonly noteHint: string;
  readonly working: string;
  readonly grant: string;
  /** Carries `{capabilities}`, `{role}`. */
  readonly confers: string;
  readonly doneTitle: string;
  readonly failedTitle: string;
  /** Carries `{id}`, `{role}`. */
  readonly grantedNotice: string;
  /** Carries `{id}`, `{role}`. */
  readonly withdrawnNotice: string;
  readonly role: Readonly<Record<StaffRole, string>>;
  readonly capability: Readonly<Record<StaffCapability, string>>;
}

export function staffRolesCopyFrom(
  t: AdminTranslator,
  chrome: ConsoleChromeCopy,
): StaffRolesCopy {
  return {
    ...chrome,
    subject: t('screens.staff.subject'),
    meSubject: t('screens.staff.meSubject'),
    myHeading: t('screens.staff.myHeading'),
    loadingMe: t('screens.staff.loadingMe'),
    notStaffTitle: t('screens.staff.notStaffTitle'),
    notStaffBody: t('screens.staff.notStaffBody'),
    signedInAs: String(t.raw('screens.staff.signedInAs')),
    bootstrapTitle: t('screens.staff.bootstrapTitle'),
    bootstrapBody: String(t.raw('screens.staff.bootstrapBody')),
    rosterHeading: t('screens.staff.rosterHeading'),
    rosterForbiddenTitle: t('screens.staff.rosterForbiddenTitle'),
    rosterForbiddenBody: String(t.raw('screens.staff.rosterForbiddenBody')),
    loadingRoster: t('screens.staff.loadingRoster'),
    rosterEmptyTitle: t('screens.staff.rosterEmptyTitle'),
    rosterEmptyBody: t('screens.staff.rosterEmptyBody'),
    grantedBy: String(t.raw('screens.staff.grantedBy')),
    withdraw: t('screens.staff.withdraw'),
    grantHeading: t('screens.staff.grantHeading'),
    grantIntro: t('screens.staff.grantIntro'),
    accountLabel: t('screens.staff.accountLabel'),
    accountHint: t('screens.staff.accountHint'),
    roleLabel: t('screens.staff.roleLabel'),
    noteLabel: t('screens.staff.noteLabel'),
    noteHint: t('screens.staff.noteHint'),
    working: t('screens.staff.working'),
    grant: t('screens.staff.grant'),
    confers: String(t.raw('screens.staff.confers')),
    doneTitle: t('screens.staff.doneTitle'),
    failedTitle: t('screens.staff.failedTitle'),
    grantedNotice: String(t.raw('screens.staff.grantedNotice')),
    withdrawnNotice: String(t.raw('screens.staff.withdrawnNotice')),
    role: t.raw('screens.staff.role') as Readonly<Record<StaffRole, string>>,
    capability: t.raw('screens.staff.capability') as Readonly<Record<StaffCapability, string>>,
  };
}
