/**
 * The administration console's sixteen modules — §4.11, issue #294.
 *
 * <h2>Why this file is a list of what does not exist as much as what does</h2>
 *
 * Epic #259's definition of done is that "every module in §4.11's table has either a screen
 * or an open blocker naming what it waits on". A navigation listing only the built screens
 * would satisfy the letter of that and lose the point: a console showing nine entries reads
 * as a console that is nine screens, and the fourteen absent modules become something a new
 * member of staff discovers by asking. So every module is here, each carries its state, and
 * the ones that are not built say what they are waiting for and which issue owns it.
 *
 * That is the opposite of the rule `components/shell/navigation.ts` states for the public
 * site — "it lists what exists, and nothing else" — and the difference is who is reading.
 * A visitor offered a link to a page that does not exist has been lied to. A member of staff
 * told that refunds are not built yet, and why, has been told something true and useful, and
 * the alternative is that they go looking for the screen.
 *
 * <strong>Nothing here renders as a link unless {@link ConsoleModule.href} is set.</strong>
 * A blocked module is text with a reason beside it. There is no href to forget to remove.
 *
 * <h2>Not translated, unlike the account area's navigation</h2>
 *
 * `apps/web/README.md` records the split: the message catalogue covers the account area
 * only, because reading a locale cookie makes a render dynamic and those routes are
 * per-person already. The console is per-person too, so the cost argument does not apply —
 * what does apply is that §21.1's catalogue is for the product's readers, and the console's
 * readers are the four people who operate it. Translating "Chargebacks" into four languages
 * before anybody has asked would be four times the strings to keep current for an audience
 * that has not existed yet. The day the platform has staff who do not read English, this
 * file gains keys the way `lib/account/navigation.ts` did.
 */

/** What the platform can actually offer for a module today. */
export type ModuleState =
  /** A screen exists and this entry links to it. */
  | 'built'
  /** Partly built: the screen is there and part of the module is not. The note says which. */
  | 'partial'
  /** No screen, and the note says what it is waiting on rather than "coming soon". */
  | 'blocked';

export interface ConsoleModule {
  /** §4.11's identifier, which is how the specification, the issues and this file agree. */
  readonly code: string;
  /** §4.11's name for the module. */
  readonly title: string;
  /** What the module is, in one sentence a reader can act on. */
  readonly summary: string;
  readonly state: ModuleState;
  /**
   * The screen this module's row links to, or null when there is none.
   *
   * A blocked module has no link to follow — see {@link ConsoleIndex} on why a disabled link
   * is worse than no link.
   */
  readonly href: string | null;
  /**
   * The module's other screens, when it has more than one.
   *
   * <p>Two of §4.11's sixteen do. Curation is four screens over one endpoint set — the
   * collections, the badges they grant, the open calls, and the order they appear in — and
   * Finance is two, because what a provider was asked and what the money meant are different
   * questions over different tables. The row links to the first and the rail lists them all,
   * and this is what lets a test assert that every rail entry belongs to a module rather
   * than merely resolving.
   */
  readonly otherScreens?: readonly string[];
  /**
   * What it is waiting for, present exactly when {@link state} is not `built`.
   *
   * A sentence rather than a label. "Blocked" tells a reader to stop looking; "blocked on
   * the fee schedule table, which is not built" tells them why nobody can unblock it by
   * asking.
   */
  readonly waitingOn?: string;
  /** The issue that owns it, so a reader can go and read the argument rather than repeat it. */
  readonly issue: number;
}

/**
 * The console, in §4.11's own order.
 *
 * Frozen because it is read by the navigation, by the console index and by a test that
 * asserts the two agree; a module list something could push onto is one that eventually
 * differs between the nav and the page describing the nav.
 */
export const CONSOLE_MODULES: readonly ConsoleModule[] = Object.freeze([
  {
    code: 'AD-01',
    title: 'Project moderation',
    summary: 'The submission queue, its three outcomes, and the decision behind each one.',
    state: 'built',
    href: '/admin/moderation',
    issue: 101,
  },
  {
    code: 'AD-02',
    title: 'Trust and safety',
    summary: 'Complaints about campaigns, and the suspension that answers the worst of them.',
    state: 'partial',
    href: '/admin/moderation',
    waitingOn: 'Fraud signals are unbuilt; the queue and the suspension are not.',
    issue: 103,
  },
  {
    code: 'AD-03',
    title: 'Curation',
    summary: 'Editorial collections, the badges they grant, open calls, and where they appear.',
    state: 'built',
    href: '/admin/curation',
    otherScreens: [
      '/admin/curation/badges',
      '/admin/curation/open-calls',
      '/admin/curation/placements',
    ],
    issue: 301,
  },
  {
    code: 'AD-04',
    title: 'User management',
    summary: 'Search an account, read its standing, stop it, and let it back in.',
    state: 'partial',
    href: '/admin/users',
    waitingOn:
      'Audited impersonation is blocked on a policy answer: what a session issued in ' +
      "somebody else's name may not do is a question §17 does not settle (#299).",
    issue: 104,
  },
  {
    code: 'AD-05',
    title: 'Finance',
    summary: 'Every charge and every posting, with the balances the postings add up to.',
    state: 'partial',
    href: '/admin/payments',
    otherScreens: ['/admin/ledger'],
    waitingOn:
      'The payout queue and its approvals wait on #69, which calculates a payout; ' +
      'disputes wait on #68.',
    issue: 304,
  },
  {
    code: 'AD-06',
    title: 'Refunds',
    summary: 'Full and partial, with reason codes.',
    state: 'blocked',
    href: null,
    waitingOn: 'Blocked on #67: nothing in the service issues a refund yet.',
    issue: 307,
  },
  {
    code: 'AD-07',
    title: 'Chargebacks',
    summary: 'Notification, evidence, outcome.',
    state: 'blocked',
    href: null,
    waitingOn: 'Blocked on #68: no provider webhook raises a dispute yet.',
    issue: 308,
  },
  {
    code: 'AD-08',
    title: 'Taxonomy',
    summary: 'Categories, subcategories and tags, with a translation per locale.',
    state: 'blocked',
    href: null,
    waitingOn:
      '§4.3 requires the taxonomy be editable without a deployment, and no endpoint ' +
      'exposes it (#309).',
    issue: 309,
  },
  {
    code: 'AD-09',
    title: 'Content moderation',
    summary: 'Complaints filed against a person rather than one of their campaigns.',
    state: 'partial',
    href: '/admin/moderation/profiles',
    waitingOn:
      'Comments can be reported and updates cannot — §10.2 gives an update no report ' +
      'route, so half that queue has no intake (#297).',
    issue: 298,
  },
  {
    code: 'AD-10',
    title: 'Support',
    summary: 'Tickets with the account context and the history of what was done.',
    state: 'blocked',
    href: null,
    waitingOn: 'Blocked on there being no ticket store (#310).',
    issue: 310,
  },
  {
    code: 'AD-11',
    title: 'Fee configuration',
    summary: 'Platform and processing rates, and the exceptions to them.',
    state: 'blocked',
    href: null,
    waitingOn: 'Blocked on the fee schedule table, which is not built (#311).',
    issue: 311,
  },
  {
    code: 'AD-12',
    title: 'Feature flags',
    summary: 'Gradual rollout and experiments.',
    state: 'blocked',
    href: null,
    waitingOn: 'Blocked on there being no flag store (#312).',
    issue: 312,
  },
  {
    code: 'AD-13',
    title: 'Analytics',
    summary: 'Volume, success rate, average pledge, cohorts, funnels.',
    state: 'blocked',
    href: null,
    waitingOn: '#95 aggregates one campaign rather than the platform (#313).',
    issue: 313,
  },
  {
    code: 'AD-14',
    title: 'Audit log',
    summary: 'The immutable record of every privileged action, newest first.',
    state: 'built',
    href: '/admin/audit',
    issue: 314,
  },
  {
    code: 'AD-15',
    title: 'Email templates',
    summary: 'Preview and test send are built; editing is not.',
    state: 'blocked',
    href: null,
    waitingOn:
      'No template store, and no answer to who may rewrite a payment-failure ' +
      'notice (#315).',
    issue: 315,
  },
  {
    code: 'AD-16',
    title: 'System health',
    summary: 'Queue depth, failed jobs, provider status.',
    state: 'blocked',
    href: null,
    waitingOn: 'Blocked on #138, which is the observability work §18 describes.',
    issue: 316,
  },
]);

/** One destination in the console's navigation. */
export interface ConsoleLink {
  readonly href: string;
  readonly label: string;
}

/** One titled group of destinations. */
export interface ConsoleGroup {
  readonly heading: string;
  readonly links: readonly ConsoleLink[];
}

/**
 * The navigation, grouped by the question being asked rather than by §4.11's numbering.
 *
 * <p>AD-01, AD-02 and AD-09 are three rows of one table and two screens, so a rail that
 * followed the specification's order would put "Trust and safety" between two things that
 * are the same screen. Somebody working the console is asking "is this about content, about
 * people, about money, or about the platform" — so those are the four groups.
 *
 * <p><strong>Only built screens are here.</strong> The blocked twelve are on the console
 * index, where there is room to say what each is waiting for; a rail entry that opened a
 * page saying "not built" would be a destination in a navigation whose whole purpose is to
 * take somebody somewhere.
 */
export const CONSOLE_GROUPS: readonly ConsoleGroup[] = Object.freeze([
  {
    heading: 'Content',
    links: [
      { href: '/admin/moderation', label: 'Moderation queue' },
      { href: '/admin/moderation/profiles', label: 'Profile reports' },
    ],
  },
  {
    heading: 'Curation',
    links: [
      { href: '/admin/curation', label: 'Collections' },
      { href: '/admin/curation/badges', label: 'Editorial badges' },
      { href: '/admin/curation/open-calls', label: 'Open calls' },
      { href: '/admin/curation/placements', label: 'Placement' },
    ],
  },
  {
    heading: 'People',
    links: [{ href: '/admin/users', label: 'Accounts' }],
  },
  {
    heading: 'Money',
    links: [
      { href: '/admin/payments', label: 'Payment log' },
      { href: '/admin/ledger', label: 'Ledger' },
    ],
  },
  {
    heading: 'Platform',
    links: [{ href: '/admin/audit', label: 'Audit log' }],
  },
]);

/**
 * Whether a navigation entry names the page being rendered.
 *
 * <p>An exact match, and deliberately not a prefix match, because the console's paths nest:
 * `/admin/curation` is the collection manager and `/admin/curation/badges` is a different
 * screen. A prefix rule would mark both current on the second one, and `aria-current="page"`
 * appearing twice is worse than not appearing at all — it tells a screen reader the page is
 * in two places.
 *
 * <p>The one exception is `/admin/curation/[slug]`, a collection's own editor, which has no
 * entry of its own and belongs under Collections. It is matched here rather than left
 * unmarked, so that opening a collection does not make the rail go blank.
 */
export function isCurrentConsoleLink(href: string, pathname: string): boolean {
  if (pathname === href) return true;

  if (href === '/admin/curation') {
    return (
      pathname.startsWith('/admin/curation/') &&
      !CONSOLE_GROUPS.some((group) =>
        group.links.some((link) => link.href !== href && link.href === pathname),
      )
    );
  }

  return false;
}

/** How many of §4.11's modules have a screen, for the sentence the console index opens with. */
export function builtModuleCount(): number {
  return CONSOLE_MODULES.filter((module) => module.href !== null).length;
}

/** Every path a module owns, for a check that the rail lists nothing that is not one. */
export function screensOf(module: ConsoleModule): readonly string[] {
  return module.href === null ? [] : [module.href, ...(module.otherScreens ?? [])];
}
