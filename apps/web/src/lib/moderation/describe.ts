import type {
  CampaignOutcome,
  QueuedReport,
  ReportOutcome,
  ReportReason,
  ReportState,
  ReportTargetType,
} from './api';

/**
 * Turning a queue row into words.
 *
 * Everything here is pure and takes `now` as a parameter rather than reading the
 * clock, so the tests do not have to freeze time and every card on one render
 * measures its age from the same instant.
 */

export const REASON_LABELS: Readonly<Record<ReportReason, string>> = {
  PROHIBITED_ITEM: 'Prohibited item',
  MISREPRESENTATION: 'Misrepresentation',
  NOT_ORIGINAL: 'Not original work',
  INTELLECTUAL_PROPERTY: 'Intellectual property',
  OFFENSIVE: 'Offensive content',
  DISCRIMINATION: 'Discrimination',
  SPAM: 'Spam',
  FRAUD: 'Fraud',
  OTHER: 'Other',
};

/**
 * What was reported, in the words a moderator uses for it.
 *
 * "Campaign" rather than "project", because that is what the rest of the product
 * calls one.
 */
export const TARGET_LABELS: Readonly<Record<ReportTargetType, string>> = {
  PROJECT: 'campaign',
  PROJECT_UPDATE: 'campaign update',
  COMMENT: 'comment',
  USER: 'account',
};

export const STATE_LABELS: Readonly<Record<ReportState, string>> = {
  OPEN: 'Open',
  UPHELD: 'Upheld',
  DISMISSED: 'Dismissed',
};

/** Past tense, for the sentence that says what happened to a decided report. */
export const RESOLUTION_VERBS: Readonly<Record<ReportOutcome, string>> = {
  uphold: 'Upheld',
  dismiss: 'Dismissed',
};

export const CAMPAIGN_OUTCOME_LABELS: Readonly<Record<CampaignOutcome, string>> = {
  approve: 'Approve',
  reject: 'Reject',
  'request-changes': 'Request changes',
};

export const REPORT_OUTCOME_LABELS: Readonly<Record<ReportOutcome, string>> = {
  uphold: 'Uphold',
  dismiss: 'Dismiss',
};

export function reasonLabel(reason: ReportReason): string {
  return REASON_LABELS[reason];
}

export function targetLabel(type: ReportTargetType): string {
  return TARGET_LABELS[type];
}

/**
 * Enough of an identifier to tell two cards apart, said aloud without pain.
 *
 * The queue carries a target id and nothing else — no title, no slug, no URL —
 * so the identifier is the only name a card has. Eight characters is what git
 * settled on for the same problem.
 */
export function shortId(id: string): string {
  return id.slice(0, 8);
}

/**
 * How long a complaint may sit before the queue calls it urgent.
 *
 * THE SERVICE HAS NO SLA — there is no field on a report that says when it
 * should have been answered, so this threshold is the client's and it is stated
 * here rather than buried in a comparison. Forty-eight hours is deliberate reuse
 * of the one duration the design system already treats as urgent
 * (docs/ui-kit.md §8.1, "closing within 48 hours"), so lime keeps one meaning
 * across the product instead of two.
 */
export const OVERDUE_AFTER_MS = 48 * 60 * 60 * 1000;

/**
 * Urgent, in the sense lime is allowed to mean.
 *
 * Only an OPEN report can be overdue. A decision taken last March is not late;
 * it is finished.
 */
export function isOverdue(report: QueuedReport, now: Date): boolean {
  if (report.state !== 'OPEN') return false;

  const created = new Date(report.createdAt).getTime();
  if (Number.isNaN(created)) return false;

  return now.getTime() - created >= OVERDUE_AFTER_MS;
}

/**
 * More than one open complaint about the same target.
 *
 * The service's own words: one report about a campaign and fourteen are
 * different situations, and the second is the one that gets looked at first.
 */
export function isRepeated(report: QueuedReport): boolean {
  return report.openReportsOnTarget > 1;
}

/** "1 open report on this campaign" / "14 open reports on this campaign". */
export function openReportsLabel(report: QueuedReport): string {
  const count = report.openReportsOnTarget;
  const noun = count === 1 ? 'open report' : 'open reports';
  return `${count} ${noun} on this ${targetLabel(report.target.type)}`;
}

/* -------------------------------------------------------------------------
 * Filters
 * ---------------------------------------------------------------------- */

export type TargetFilter = 'ALL' | ReportTargetType;

/**
 * What a triager narrows the queue by.
 *
 * ONLY `state` IS A SERVER FILTER. The endpoint takes `state`, `after` and
 * `limit` and nothing else, so the other three are applied to the reports
 * already loaded — which the screen says out loud rather than letting a count
 * quietly mean something different from what it looks like.
 */
export interface QueueFilters {
  readonly state: ReportState;
  readonly target: TargetFilter;
  /** Open longer than {@link OVERDUE_AFTER_MS}. */
  readonly overdueOnly: boolean;
  /** More than one open complaint about the same target. */
  readonly repeatedOnly: boolean;
}

export const DEFAULT_FILTERS: QueueFilters = {
  state: 'OPEN',
  target: 'ALL',
  overdueOnly: false,
  repeatedOnly: false,
};

/** Whether anything is being hidden — what the "showing x of y" line is for. */
export function isRefined(filters: QueueFilters): boolean {
  return filters.target !== 'ALL' || filters.overdueOnly || filters.repeatedOnly;
}

/** Applies the three client-side narrowings, in the order they are cheapest. */
export function refine(
  reports: readonly QueuedReport[],
  filters: QueueFilters,
  now: Date,
): QueuedReport[] {
  return reports.filter((report) => {
    if (filters.target !== 'ALL' && report.target.type !== filters.target) return false;
    if (filters.repeatedOnly && !isRepeated(report)) return false;
    if (filters.overdueOnly && !isOverdue(report, now)) return false;
    return true;
  });
}

/* -------------------------------------------------------------------------
 * Time
 * ---------------------------------------------------------------------- */

const RELATIVE = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

const DIVISIONS: ReadonlyArray<readonly [amount: number, unit: Intl.RelativeTimeFormatUnit]> = [
  [60, 'second'],
  [60, 'minute'],
  [24, 'hour'],
  [7, 'day'],
  [4.34524, 'week'],
  [12, 'month'],
  [Number.POSITIVE_INFINITY, 'year'],
];

/**
 * "3 hours ago", "yesterday", "just now".
 *
 * Lower case, because every string this returns is read inside a sentence:
 * "Reported 3 hours ago".
 */
export function formatRelativeTime(iso: string, now: Date): string {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return 'an unknown time ago';

  let duration = (then.getTime() - now.getTime()) / 1000;
  if (Math.abs(duration) < 45) return 'just now';

  for (const [amount, unit] of DIVISIONS) {
    if (Math.abs(duration) < amount) return RELATIVE.format(Math.round(duration), unit);
    duration /= amount;
  }

  return RELATIVE.format(Math.round(duration), 'year');
}

/**
 * The exact timestamp, for the `title` of the relative one.
 *
 * A relative string is never the only record of when a privileged decision was
 * taken. The locale is pinned until internationalised routing lands (#123),
 * matching `lib/sessions/describe.ts`.
 */
const EXACT = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' });

export function formatExactTime(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return 'Unknown';

  return EXACT.format(at);
}

/**
 * A campaign state as a sentence fragment: `CHANGES_REQUESTED` → "changes
 * requested".
 *
 * Derived rather than tabulated, because the sixteen states are the service's
 * and a table here would be a copy to fall out of step with — the same reason
 * `lib/projects/api.ts` keeps no client-side transition table.
 */
export function humaniseState(state: string): string {
  return state.toLowerCase().replace(/_/g, ' ');
}
