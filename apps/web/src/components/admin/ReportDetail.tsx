'use client';

import { useCallback, useEffect, useState } from 'react';
import { Link } from '../../i18n/navigation';
import { EmptyState, InlineAlert, Pill, Skeleton, SkeletonGroup, Tag } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import { actionLabel, readTrail, type AuditEntry } from '../../lib/admin/audit';
import {
  consoleMessageFor,
  shortId,
  statusFor,
  wasAborted,
  type ConsoleStatus,
} from '../../lib/admin/refusals';
import {
  getReport,
  resolveReport,
  type QueuedReport,
  type ReportOutcome,
} from '../../lib/moderation/api';
import {
  REPORT_OUTCOME_LABELS,
  STATE_LABELS,
  formatExactTime,
  formatRelativeTime,
  openReportsLabel,
  reasonLabel,
  targetLabel,
} from '../../lib/moderation/describe';
import { DecisionDialog, type Decision } from '../moderation/DecisionDialog';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useRouteLocale } from '../../lib/i18n/useRouteLocale';

const SUBJECT = 'this report';

export interface ReportDetailProps {
  readonly reportId: string;
}

/**
 * §4.11's AD-01: one complaint, its notes, and the full decision history — issue #296.
 *
 * <h2>What this has that a card in the queue does not</h2>
 *
 * The queue shows what a moderator needs to decide a report and deliberately stops there:
 * twenty cards each carrying a full history is twenty histories nobody read. What is missing
 * from a card, and is the whole of this page, is <strong>the audit trail for the report</strong>
 * — every privileged action recorded against it, including the ones that were refused.
 *
 * <p>That distinction matters more than it sounds. A card's "Decision" block is the current
 * resolution: one decider, one instant, one note. The trail is the sequence, and the sequence
 * is where "somebody tried to uphold this and was refused because they are not staff" lives.
 * A screen that showed only the resolution would answer "what was decided" and could not
 * answer "what happened", and the second question is the one an appeal is about.
 *
 * <h2>Two reads, and neither blocks the other</h2>
 *
 * The report and its history come from two endpoints — `GET /v1/admin/moderation/reports/{id}`
 * and `GET /v1/admin/audit?entityType=report&entityId={id}`, the second of which only became
 * readable with #314. They are requested together and rendered independently: a trail that
 * fails to load leaves the report and its decisions on screen with a line saying the history
 * is missing, rather than replacing a working page with an error.
 *
 * <h2>The decisions are here too, and they are the same two</h2>
 *
 * Upholding and dismissing are reachable from this page as well as from the queue, through the
 * same dialog — one place a decision is committed, and one set of words for what it costs.
 * <strong>The campaign outcomes are not.</strong> Approving or rejecting a campaign is a
 * different privileged action on a different object, it is offered on the queue where the
 * campaign's own state is in view, and putting it on a page titled after a complaint is how a
 * moderator ends up suspending a campaign they meant to write a note about.
 *
 * <h2>Motion</h2>
 *
 * The modal's 200ms entry, and nothing else — docs/motion-system.md §5's budget for a working
 * surface, and the same one the queue keeps.
 */
export function ReportDetail({ reportId }: ReportDetailProps) {
  const locale = useRouteLocale();
  const [status, setStatus] = useState<ConsoleStatus>('loading');
  const [report, setReport] = useState<QueuedReport | null>(null);
  const [history, setHistory] = useState<readonly AuditEntry[] | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [attempt, setAttempt] = useState(0);

  const [pending, setPending] = useState<Decision | null>(null);
  const [dialogBusy, setDialogBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);

  const loadHistory = useCallback(
    async (signal?: AbortSignal): Promise<void> => {
      try {
        const page = await readTrail({ entityType: 'report', entityId: reportId, signal });
        if (signal?.aborted === true) return;
        setHistory(page.entries);
        setHistoryError(null);
      } catch (cause) {
        if (signal?.aborted === true || wasAborted(cause)) return;
        /*
         * The history failing is not the page failing. The report and its resolution are
         * what a moderator came for; the trail is the second question, and losing it should
         * cost a line of text rather than the screen.
         */
        setHistoryError(consoleMessageFor(cause, 'the decision history'));
      }
    },
    [reportId],
  );

  useEffect(() => {
    const controller = new AbortController();

    async function load(): Promise<void> {
      setStatus('loading');
      setNotFound(false);
      try {
        const loaded = await getReport(reportId, controller.signal);
        if (controller.signal.aborted) return;

        setReport(loaded);
        setError(null);
        setStatus('ready');
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;

        if (cause instanceof ApiError && cause.status === 404) {
          setNotFound(true);
          setStatus('ready');
          return;
        }
        const next = statusFor(cause);
        if (next === 'failed') setError(consoleMessageFor(cause, SUBJECT));
        setStatus(next);
      }
    }

    void load();
    void loadHistory(controller.signal);
    return () => controller.abort();
  }, [reportId, attempt, loadHistory]);

  async function commit(note: string | null): Promise<void> {
    if (pending === null || report === null || pending.kind !== 'report') return;

    setDialogBusy(true);
    setDialogError(null);
    try {
      const updated = await resolveReport(report.id, pending.outcome, note);
      setReport(updated);
      setPending(null);
      /*
       * The decision that was just taken is a row in the trail, so the trail is re-read
       * rather than appended to from what this screen believes it did. A history assembled
       * from the client's own actions is a history that disagrees with the record the
       * moment two people are working.
       */
      await loadHistory();
    } catch (cause) {
      if (cause instanceof ApiError && cause.problem?.code === 'REPORT_ALREADY_RESOLVED') {
        // Somebody else decided it first. Show what they decided rather than what was tried.
        try {
          setReport(await getReport(reportId));
          await loadHistory();
        } catch {
          // The correction failed too; the message below still says what happened.
        }
        setPending(null);
        setError(consoleMessageFor(cause, SUBJECT));
        return;
      }
      setDialogError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setDialogBusy(false);
    }
  }

  if (status === 'signed-out' || status === 'forbidden') {
    return <ConsoleRefusal status={status} subject={SUBJECT} />;
  }

  if (status === 'loading') {
    return (
      <SkeletonGroup label="Loading the report">
        <div className="rounded-xl border border-white/8 bg-surface-1 p-5">
          <Skeleton height="1.25rem" width="40%" />
          <Skeleton height="0.875rem" width="60%" className="mt-3" />
          <Skeleton height="0.875rem" width="80%" className="mt-2" />
        </div>
      </SkeletonGroup>
    );
  }

  if (notFound) {
    return (
      <EmptyState
        variant="empty"
        title="No such report"
        description="That identifier does not name a report. It may have been decided and removed, or the link may be wrong."
        action={
          <Link
            href="/admin/moderation"
            className="rounded-lg text-sm text-white/64 underline underline-offset-2 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            Back to the queue
          </Link>
        }
      />
    );
  }

  if (report === null) {
    return (
      <>
        {error && (
          <InlineAlert variant="danger" title="Something went wrong">
            {error}
          </InlineAlert>
        )}
        <Pill variant="ghost" size="sm" className="mt-4" onClick={() => setAttempt((n) => n + 1)}>
          Try again
        </Pill>
      </>
    );
  }

  const now = new Date();
  const kind = targetLabel(report.target.type);
  const open = report.state === 'OPEN';

  return (
    <div>
      {error && (
        <InlineAlert variant="danger" title="Something went wrong" className="mb-4">
          {error}
        </InlineAlert>
      )}

      <section
        aria-labelledby="report-heading"
        className="rounded-xl border border-white/8 bg-surface-1 p-5"
      >
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2
              id="report-heading"
              className="text-lg font-medium tracking-[-0.02em] text-white"
            >
              {reasonLabel(report.reason)}
            </h2>
            <p className="mt-1 text-sm text-white/64">
              Reported about {kind}{' '}
              <span className="font-mono" title={report.target.id}>
                {report.target.id}
              </span>
            </p>
          </div>
          <Tag variant={report.state === 'UPHELD' ? 'success' : 'default'}>
            {STATE_LABELS[report.state]}
          </Tag>
        </div>

        <dl className="mt-4 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <div className="flex gap-2">
            <dt className="text-white/40">Reported</dt>
            <dd className="text-white/64">
              <time dateTime={report.createdAt} title={formatExactTime(report.createdAt, locale)}>
                {formatRelativeTime(report.createdAt, now, locale)}
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
              An account id and not a name. A report is an accusation, "this account has
              reported forty campaigns this week" is the question that catches abuse of the
              feature, and nothing turns an identifier into a person here.
            */}
            <dd className="font-mono text-white/64" title={report.reporterId}>
              {shortId(report.reporterId)}
            </dd>
          </div>
        </dl>

        {/* Untrusted: what one person wrote about another, rendered only ever as text. */}
        {report.detail != null && report.detail !== '' && (
          <blockquote className="mt-4 rounded-md border-l-2 border-white/16 bg-surface-3 py-3 pl-4 pr-3 text-sm text-white/64">
            {report.detail}
          </blockquote>
        )}

        {report.resolution != null && (
          <div className="mt-4 rounded-md bg-surface-3 p-4">
            <h3 className="text-sm font-medium text-white">Decision</h3>
            <p className="mt-1 text-sm text-white/64">
              {STATE_LABELS[report.state]} by moderator{' '}
              <span className="font-mono" title={report.resolution.moderatorId}>
                {shortId(report.resolution.moderatorId)}
              </span>{' '}
              on <time dateTime={report.resolution.at}>{formatExactTime(report.resolution.at, locale)}</time>.
            </p>
            {report.resolution.note != null && report.resolution.note !== '' ? (
              <p className="mt-2 text-sm text-white/64">{report.resolution.note}</p>
            ) : (
              <p className="mt-2 text-sm text-white/40">No note was left.</p>
            )}
          </div>
        )}

        {open && (
          <div className="mt-5" role="group" aria-label="Decide this complaint">
            <p className="text-xs font-medium uppercase tracking-wide text-white/40">
              Decide the complaint
            </p>
            <div className="mt-2 flex flex-wrap gap-2">
              {(['uphold', 'dismiss'] as const).map((outcome: ReportOutcome) => (
                <Pill
                  key={outcome}
                  variant="outline"
                  size="sm"
                  disabled={dialogBusy}
                  onClick={() => {
                    setDialogError(null);
                    setPending({ kind: 'report', outcome });
                  }}
                >
                  {REPORT_OUTCOME_LABELS[outcome]}
                </Pill>
              ))}
            </div>
            <p className="mt-3 max-w-[62ch] text-sm text-white/40">
              This records a judgement about the complaint and nothing else. Suspending the
              campaign, banning the account and removing the content are separate decisions
              with separate consequences, and none of them happens here.
            </p>
          </div>
        )}
      </section>

      <section aria-labelledby="report-history-heading" className="mt-8">
        <h2
          id="report-history-heading"
          className="text-lg font-medium tracking-[-0.02em] text-white"
        >
          History
        </h2>
        <p className="mt-1 max-w-[62ch] text-sm text-white/48">
          Every privileged action recorded against this report, newest first — including the
          attempts that were refused. Read back from the audit trail rather than remembered by
          this screen, which is why it can answer for decisions taken last March.
        </p>

        {historyError !== null && (
          <InlineAlert variant="info" title="The history could not be read" className="mt-4">
            {historyError}
          </InlineAlert>
        )}

        {historyError === null && history === null && (
          <SkeletonGroup label="Loading the decision history" className="mt-4">
            <Skeleton height="0.875rem" width="60%" />
            <Skeleton height="0.875rem" width="45%" className="mt-2" />
          </SkeletonGroup>
        )}

        {history !== null && history.length === 0 && (
          <p className="mt-4 text-sm text-white/40">
            Nothing has been done to this report yet. A row appears here the moment somebody
            decides it.
          </p>
        )}

        {history !== null && history.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-2">
            {history.map((entry) => (
              <li key={entry.id} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <p className="text-sm text-white">
                    {actionLabel(entry.action)}
                    {entry.actorId != null && (
                      <>
                        {' by '}
                        <span className="font-mono text-white/64" title={entry.actorId}>
                          {shortId(entry.actorId)}
                        </span>
                      </>
                    )}
                  </p>
                  <div className="flex items-center gap-2">
                    {entry.outcome === 'REFUSED' && <Tag variant="warning">Refused</Tag>}
                    <time
                      dateTime={entry.occurredAt}
                      className="text-xs text-white/40"
                      title={entry.occurredAt}
                    >
                      {new Date(entry.occurredAt).toLocaleString()}
                    </time>
                  </div>
                </div>
                {entry.detail != null && entry.detail !== '' && (
                  <p className="mt-2 text-sm text-white/48">{entry.detail}</p>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <DecisionDialog
        decision={pending}
        report={pending === null ? null : report}
        busy={dialogBusy}
        error={dialogError}
        onCancel={() => {
          setPending(null);
          setDialogError(null);
        }}
        onConfirm={(note) => void commit(note)}
      />
    </div>
  );
}
