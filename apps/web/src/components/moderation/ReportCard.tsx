'use client';

import Link from 'next/link';
import { Card, Pill, Tag } from '@ideanest/ui';
import type { QueuedReport } from '../../lib/moderation/api';
import {
  CAMPAIGN_OUTCOME_LABELS,
  REPORT_OUTCOME_LABELS,
  STATE_LABELS,
  formatExactTime,
  formatRelativeTime,
  isOverdue,
  isRepeated,
  openReportsLabel,
  reasonLabel,
  shortId,
  targetLabel,
} from '../../lib/moderation/describe';
import type { Decision } from './DecisionDialog';

export interface ReportCardProps {
  readonly report: QueuedReport;
  /** Pinned per load, so every card on one render ages from the same instant. */
  readonly now: Date;
  readonly busy: boolean;
  /**
   * The decision detail view for this report (#296), or absent where there is none.
   *
   * <p>A card carries everything needed to decide a report and deliberately not everything
   * known about one: the full decision history, and the other complaints about the same
   * target, belong on a page rather than on a row in a queue somebody is skimming.
   */
  readonly detailHref?: string;
  readonly onDecide: (report: QueuedReport, decision: Decision) => void;
}

const REPORT_OUTCOMES = ['uphold', 'dismiss'] as const;
const CAMPAIGN_OUTCOMES = ['approve', 'request-changes', 'reject'] as const;

/**
 * The state word, with a token that means what the word means.
 *
 * `UPHELD` is `--success` and never lime: the complaint was answered, and lime
 * says "act now" (docs/ui-kit.md §2.4). `DISMISSED` is neutral — a dismissal is
 * not a failure and colouring it `--danger` would read as one. The word is
 * always present, so colour is never the carrier (§9.2).
 */
function stateVariant(report: QueuedReport): 'success' | 'default' {
  return report.state === 'UPHELD' ? 'success' : 'default';
}

/**
 * One complaint, everything a moderator needs to decide it, and the five things
 * they can do about it.
 *
 * NO ENTRY ANIMATION. docs/motion-system.md §5 puts a working tool at "minimal"
 * or "none", §8 rules out animation in long lists outright, and this is both. A
 * card fading in under somebody triaging abuse reports is 300ms in which the
 * button cannot be aimed at. Hover is the standard 150ms colour change `Card`
 * already carries and nothing else moves.
 */
export function ReportCard({ report, now, busy, detailHref, onDecide }: ReportCardProps) {
  const kind = targetLabel(report.target.type);
  const target = `${kind} ${shortId(report.target.id)}`;
  const overdue = isOverdue(report, now);
  const open = report.state === 'OPEN';
  const headingId = `report-${report.id}-heading`;

  return (
    <li>
      <Card
        aria-labelledby={headingId}
        className={busy ? 'opacity-60' : undefined}
        aria-busy={busy || undefined}
      >
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h3
              id={headingId}
              className="text-lg font-medium tracking-[-0.02em] text-white"
            >
              {reasonLabel(report.reason)}
            </h3>
            <p className="mt-1 text-sm text-white/64">
              Reported about {kind}{' '}
              <span className="font-mono text-white/64">{shortId(report.target.id)}</span>
            </p>
            {/*
              The name says which report, for the reason the decision buttons below give:
              twenty cards offering "Open" is unusable by ear, and this one leads to a
              screen about somebody's account.
            */}
            {detailHref !== undefined && (
              <Link
                href={detailHref}
                aria-label={`Open the full history of the report about ${target}`}
                className="mt-2 inline-block rounded-lg text-sm text-white/64 underline underline-offset-2 transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
              >
                Full history
              </Link>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {/*
              Lime is a SURFACE with near-black text on it, and it is the one
              lime element on the card — docs/ui-kit.md §8.2's rule for a
              filtered feed, which this is: the queue is sorted oldest first and
              a backlog would otherwise turn every card lime and say nothing.
              The word carries the meaning; the colour only makes it findable.
            */}
            {overdue && (
              <span
                data-on-lime
                className="inline-flex h-[26px] items-center rounded-sm bg-lime-500 px-2.5 text-xs font-medium text-on-lime"
              >
                Overdue
              </span>
            )}
            {isRepeated(report) && <Tag variant="warning">{report.openReportsOnTarget} reports</Tag>}
            <Tag variant={stateVariant(report)}>{STATE_LABELS[report.state]}</Tag>
          </div>
        </div>

        <dl className="mt-4 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <div className="flex gap-2">
            <dt className="text-white/40">Reported</dt>
            <dd className="text-white/64">
              <time dateTime={report.createdAt} title={formatExactTime(report.createdAt)}>
                {formatRelativeTime(report.createdAt, now)}
              </time>
            </dd>
          </div>
          <div className="flex gap-2">
            <dt className="text-white/40">Signal</dt>
            <dd className="text-white/64">{openReportsLabel(report)}</dd>
          </div>
          <div className="flex gap-2">
            <dt className="text-white/40">Reporter</dt>
            {/*
              An account id, not a name — a report is an accusation and "this
              account has reported forty campaigns this week" is the question
              that catches abuse of the feature. Nothing turns an id into a
              person yet, so the card says what it is showing.
            */}
            <dd className="font-mono text-white/64">{shortId(report.reporterId)}</dd>
          </div>
          <div className="flex gap-2">
            <dt className="text-white/40">Target</dt>
            <dd className="font-mono text-white/64">{report.target.id}</dd>
          </div>
        </dl>

        {report.detail != null && report.detail !== '' && (
          <blockquote className="mt-4 rounded-md border-l-2 border-white/16 bg-surface-3 py-3 pl-4 pr-3 text-sm text-white/64">
            {report.detail}
          </blockquote>
        )}

        {/*
          HISTORY. Read back from the audited record the service returns, not
          reconstructed from what this screen happens to have done — a queue that
          remembers only its own session cannot answer who decided something last
          March.
        */}
        {report.resolution != null && (
          <div className="mt-4 rounded-md bg-surface-3 p-4">
            <h4 className="text-sm font-medium text-white">Decision</h4>
            <p className="mt-1 text-sm text-white/64">
              {STATE_LABELS[report.state]} by moderator{' '}
              <span className="font-mono">{shortId(report.resolution.moderatorId)}</span> on{' '}
              <time dateTime={report.resolution.at}>
                {formatExactTime(report.resolution.at)}
              </time>
              .
            </p>
            {report.resolution.note != null && report.resolution.note !== '' ? (
              <p className="mt-2 text-sm text-white/64">{report.resolution.note}</p>
            ) : (
              <p className="mt-2 text-sm text-white/40">No note was left.</p>
            )}
          </div>
        )}

        {open && (
          <div className="mt-5 space-y-4">
            <div role="group" aria-label={`Decide the report about ${target}`}>
              <p className="text-xs font-medium uppercase tracking-wide text-white/40">
                Decide the complaint
              </p>
              <div className="mt-2 flex flex-wrap gap-2">
                {REPORT_OUTCOMES.map((outcome) => (
                  <Pill
                    key={outcome}
                    variant="outline"
                    size="sm"
                    disabled={busy}
                    /*
                      The visible word is inside the accessible name, so speech
                      input can still reach the control by what is printed on it
                      (WCAG 2.5.3), and the name says which report it acts on —
                      twenty cards of "Uphold" is unusable by ear and dangerous
                      here, where every button is irreversible.
                    */
                    aria-label={`${REPORT_OUTCOME_LABELS[outcome]} the report about ${target}`}
                    onClick={() => onDecide(report, { kind: 'report', outcome })}
                  >
                    {REPORT_OUTCOME_LABELS[outcome]}
                  </Pill>
                ))}
              </div>
            </div>

            {/*
              The campaign outcomes, and only for a campaign. Deciding a report
              does not act on what was reported, so these are a second, separate
              privileged action — said in words, because a moderator who thinks
              one button did both stops half way through the job.

              There is no `GET /v1/admin/moderation/queue` in the service, so a
              report about a campaign is the only place these three are
              reachable from.
            */}
            {report.target.type === 'PROJECT' && (
              <div role="group" aria-label={`Act on ${target}`}>
                <p className="text-xs font-medium uppercase tracking-wide text-white/40">
                  Act on the campaign — separate from the report
                </p>
                <div className="mt-2 flex flex-wrap gap-2">
                  {CAMPAIGN_OUTCOMES.map((outcome) => (
                    <Pill
                      key={outcome}
                      variant="outline"
                      size="sm"
                      disabled={busy}
                      aria-label={`${CAMPAIGN_OUTCOME_LABELS[outcome]} for ${target}`}
                      onClick={() => onDecide(report, { kind: 'campaign', outcome })}
                    >
                      {CAMPAIGN_OUTCOME_LABELS[outcome]}
                    </Pill>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </Card>
    </li>
  );
}
