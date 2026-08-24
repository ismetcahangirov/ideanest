'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Chip, ChipRow, EmptyState, InlineAlert, Pill, Skeleton, SkeletonGroup } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import {
  QUEUE_PAGE_SIZE,
  REPORT_STATES,
  decideCampaign,
  getReport,
  listReports,
  resolveReport,
  type QueuedReport,
  type ReportTargetType,
} from '../../lib/moderation/api';
import {
  DEFAULT_FILTERS,
  STATE_LABELS,
  humaniseState,
  isRefined,
  refine,
  shortId,
  targetLabel,
  targetParameter,
  type QueueFilters,
  type TargetFilter,
} from '../../lib/moderation/describe';
import { DecisionDialog, type Decision } from './DecisionDialog';
import { ReportCard } from './ReportCard';

type Status = 'loading' | 'ready' | 'failed' | 'signed-out' | 'forbidden';

interface Pending {
  readonly report: QueuedReport;
  readonly decision: Decision;
}

const TARGET_FILTERS: ReadonlyArray<readonly [TargetFilter, string]> = [
  ['ALL', 'Everything'],
  ['PROJECT', 'Campaigns'],
  ['USER', 'Accounts'],
];

/** `meta` is `Record<string, unknown>`, so it is narrowed rather than asserted. */
function metaString(meta: Record<string, unknown> | undefined, key: string): string | null {
  const value = meta?.[key];
  return typeof value === 'string' ? value : null;
}

/**
 * Turns a refusal into something a moderator can act on.
 *
 * Branches on `problem.code` and never on prose — two 409s that cannot be told
 * apart would force this screen to match on sentences the service is free to
 * reword.
 */
function messageFor(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 403) {
      return 'Your account is not on the platform moderator list, so this queue is not yours to clear.';
    }

    const code = cause.problem?.code;

    if (code === 'PROJECT_TRANSITION_NOT_ALLOWED') {
      const state = metaString(cause.problem?.meta, 'state');
      return state === null
        ? 'That campaign is not in a state this outcome can be reached from.'
        : `That campaign is ${humaniseState(state)}, and this outcome cannot be reached from there. The report itself is still yours to uphold or dismiss.`;
    }

    if (code === 'REPORT_ALREADY_RESOLVED') {
      return 'Somebody else decided this report first. The card now shows their decision.';
    }

    return (
      cause.problem?.detail ?? cause.problem?.title ?? 'The service refused the request. Try again.'
    );
  }
  return 'The service could not be reached. Check your connection and try again.';
}

function wasAborted(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}

function reports(count: number): string {
  return `${count} ${count === 1 ? 'report' : 'reports'}`;
}

export interface ModerationQueueProps {
  /**
   * Narrows the queue to one kind of reported thing, and takes the chip row away.
   *
   * <p>Absent on `/admin/moderation`, which is every complaint the platform has — that is
   * the screen #101 built and this parameter does not change it. Set on
   * `/admin/moderation/profiles`, where AD-09 asks for the reports filed against a person
   * rather than one of their campaigns, and where offering a chip that could widen back to
   * campaigns would be offering to turn one screen into the other.
   *
   * <p><strong>It is a server filter either way.</strong> The endpoint narrows, so the
   * cursor a client holds describes the queue it is actually reading — see
   * `lib/moderation/api.ts` on why narrowing a loaded page instead would leave rows nothing
   * could ask for.
   */
  readonly pinnedTarget?: ReportTargetType;
  /**
   * The path the decision detail view (#296) lives under, or absent for no link at all.
   *
   * <p>A string and not a function that builds the href, which is what this was first
   * written as. Both pages that render this queue are server components, and a function
   * prop cannot cross that boundary — Next refuses it at build time rather than at runtime,
   * which is the better of the two but is still a defect caught by the build rather than by
   * a type. A prefix is data and travels.
   *
   * <p>Absent by default: a queue rendered somewhere the console is not should not grow a
   * link to a route that may not be reachable from there.
   */
  readonly detailHrefBase?: string;
}

/**
 * AD-02's report queue, and the five decisions that clear it.
 *
 * THE DECISIONS ARE NOT OPTIMISTIC, deliberately. Every one of them is
 * privileged, audited and terminal, and the whole card goes busy until the
 * service answers — a row that flips to "Upheld" before the request lands, on a
 * screen where the request can be refused because another moderator got there
 * first, is an interface that lies for a few hundred milliseconds about
 * something irreversible. What arrives back is the service's own projection of
 * the report and it replaces the row wholesale; nothing here merges a guess into
 * state. A refusal leaves the card exactly as it was, with the reason on it.
 *
 * ONE COMPONENT SERVES TWO SCREENS since #298 — the whole queue and AD-09's profile
 * queue — because they differ in exactly one query parameter and in one chip row. A second
 * component would have been the same four hundred lines with a constant changed, and the
 * copy that got the next fix would have been whichever one somebody was looking at.
 *
 * MOTION is the modal's 200ms entry and 150ms of colour on hover. Nothing else.
 * docs/motion-system.md §5: motion decreases as the work gets more consequential,
 * and §8 forbids animation in long lists regardless.
 */
export function ModerationQueue({ pinnedTarget, detailHrefBase }: ModerationQueueProps = {}) {
  const [status, setStatus] = useState<Status>('loading');
  const [filters, setFilters] = useState<QueueFilters>(() =>
    pinnedTarget === undefined ? DEFAULT_FILTERS : { ...DEFAULT_FILTERS, target: pinnedTarget },
  );
  const [loaded, setLoaded] = useState<readonly QueuedReport[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [now, setNow] = useState<Date>(() => new Date());
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  const [busyIds, setBusyIds] = useState<ReadonlySet<string>>(() => new Set());

  const [pending, setPending] = useState<Pending | null>(null);
  const [dialogBusy, setDialogBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);

  const headingRef = useRef<HTMLHeadingElement>(null);

  const state = filters.state;
  const target = targetParameter(filters.target);

  /*
   * Keyed on the two server filters and on `attempt`. The other two narrow what is already
   * loaded, so changing one must not throw the loaded pages away and start the cursor
   * again — and `target` moved into this list with #298 precisely because it stopped being
   * one of those.
   */
  useEffect(() => {
    const controller = new AbortController();

    async function load(): Promise<void> {
      setStatus('loading');
      try {
        const page = await listReports({
          state,
          target,
          limit: QUEUE_PAGE_SIZE,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;

        setLoaded(page.reports);
        setCursor(page.nextCursor ?? null);
        setNow(new Date());
        setError(null);
        setStatus('ready');
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;

        if (cause instanceof ApiError && cause.status === 401) {
          setStatus('signed-out');
          return;
        }
        if (cause instanceof ApiError && cause.status === 403) {
          setStatus('forbidden');
          return;
        }
        setError(messageFor(cause));
        setStatus('failed');
      }
    }

    void load();
    return () => controller.abort();
  }, [state, target, attempt]);

  const loadMore = useCallback(async (): Promise<void> => {
    if (cursor === null || loadingMore) return;

    setLoadingMore(true);
    setError(null);
    try {
      const page = await listReports({ state, target, after: cursor, limit: QUEUE_PAGE_SIZE });
      setLoaded((previous) => [...previous, ...page.reports]);
      setCursor(page.nextCursor ?? null);
    } catch (cause) {
      setError(messageFor(cause));
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, loadingMore, state, target]);

  function markBusy(id: string, busy: boolean): void {
    setBusyIds((previous) => {
      const next = new Set(previous);
      if (busy) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /**
   * Puts the service's own version of a report back into the list.
   *
   * A report whose new state is not the one being looked at has left this queue,
   * so it is removed rather than left behind showing a state the filter says is
   * not here.
   */
  function applyReport(updated: QueuedReport): void {
    setLoaded((previous) =>
      updated.state === state
        ? previous.map((row) => (row.id === updated.id ? updated : row))
        : previous.filter((row) => row.id !== updated.id),
    );
  }

  function openDialog(report: QueuedReport, decision: Decision): void {
    setDialogError(null);
    setPending({ report, decision });
  }

  function closeDialog(): void {
    setPending(null);
    setDialogError(null);
  }

  /**
   * The one place a decision is sent, and the one place a refusal is shown.
   *
   * A `409 REPORT_ALREADY_RESOLVED` is not simply reported: the report is
   * re-read and the card replaced, so the moderator ends up looking at what
   * actually happened instead of at a queue that still offers two buttons for a
   * decision somebody else already took.
   */
  async function commit(note: string | null): Promise<void> {
    if (pending === null) return;

    const { report, decision } = pending;
    setDialogBusy(true);
    setDialogError(null);
    setError(null);
    markBusy(report.id, true);

    try {
      if (decision.kind === 'report') {
        const updated = await resolveReport(report.id, decision.outcome, note);
        applyReport(updated);
        setNotice(
          `${STATE_LABELS[updated.state]} the report about ${targetLabel(report.target.type)} ${shortId(report.target.id)}.`,
        );
      } else {
        const decided = await decideCampaign(report.target.id, decision.outcome, note);
        setNotice(
          `Campaign ${shortId(decided.id)} is now ${humaniseState(decided.state)}. The report is still open — decide it separately.`,
        );
      }

      setPending(null);
      /*
       * The button that opened the dialog may have gone with its card. `Modal`
       * returns focus to it, which lands on a detached node; moving focus to the
       * heading puts a keyboard user somewhere that still exists and says where
       * they are.
       */
      headingRef.current?.focus();
    } catch (cause) {
      const message = messageFor(cause);

      if (cause instanceof ApiError && cause.problem?.code === 'REPORT_ALREADY_RESOLVED') {
        try {
          applyReport(await getReport(report.id));
        } catch {
          // The correction failed too. The message below still says what
          // happened, and the row stays as it was rather than being guessed at.
        }
        setPending(null);
        /*
         * The message goes on the QUEUE and not on the card. Both resolutions
         * are terminal, so a report refused this way has by definition just left
         * the open queue — a message pinned to its card would be a correction
         * rendered into a node that no longer exists.
         */
        setError(message);
        setNotice(null);
        headingRef.current?.focus();
        return;
      }

      // Everything else keeps the dialog open with the note still in it: nothing
      // changed, and retyping a rejection reason is the last thing a refused
      // moderator should have to do.
      setDialogError(message);
    } finally {
      setDialogBusy(false);
      markBusy(report.id, false);
    }
  }

  if (status === 'signed-out') {
    return (
      <InlineAlert variant="info" title="You are signed out">
        This browser no longer has a session. Sign in again to read the moderation queue.
      </InlineAlert>
    );
  }

  if (status === 'forbidden') {
    return (
      <InlineAlert variant="info" title="Not a moderator">
        The report queue is read and cleared by platform staff. Your account is not on the
        configured moderator list.
      </InlineAlert>
    );
  }

  const visible = refine(loaded, filters, now);
  const refined = isRefined(filters);

  return (
    <section aria-labelledby="moderation-queue-heading">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2
          id="moderation-queue-heading"
          ref={headingRef}
          tabIndex={-1}
          className="text-lg font-medium tracking-[-0.02em] text-white"
        >
          {STATE_LABELS[state]} reports
          {status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">{visible.length}</span>
          )}
        </h2>
      </div>

      {/*
        The filter rail. `Status` and `What was reported` reach the service; the two under
        `Triage` narrow what is already loaded, and the line below the rail says so rather
        than letting a count quietly mean something different from what it looks like.
      */}
      <div className="mt-4 space-y-3">
        <ChipRow aria-label="Status">
          {REPORT_STATES.map((option) => (
            <Chip
              key={option}
              active={state === option}
              onClick={() => setFilters((previous) => ({ ...previous, state: option }))}
            >
              {STATE_LABELS[option]}
            </Chip>
          ))}
        </ChipRow>

        {/*
          Absent when the screen is pinned to one kind. A chip row on the profile queue
          whose first entry widens back to everything is a control that turns the screen
          into a different screen, under a heading that still says profiles.
        */}
        {pinnedTarget === undefined && (
          <ChipRow aria-label="What was reported">
            {TARGET_FILTERS.map(([value, label]) => (
              <Chip
                key={value}
                active={filters.target === value}
                onClick={() => setFilters((previous) => ({ ...previous, target: value }))}
              >
                {label}
              </Chip>
            ))}
          </ChipRow>
        )}

        <ChipRow aria-label="Triage">
          <Chip
            active={filters.overdueOnly}
            onClick={() =>
              setFilters((previous) => ({ ...previous, overdueOnly: !previous.overdueOnly }))
            }
          >
            Open over 48 hours
          </Chip>
          <Chip
            active={filters.repeatedOnly}
            onClick={() =>
              setFilters((previous) => ({ ...previous, repeatedOnly: !previous.repeatedOnly }))
            }
          >
            More than one report
          </Chip>
        </ChipRow>
      </div>

      <div role="status" aria-live="polite" className="empty:hidden">
        {notice && (
          <InlineAlert variant="success" className="mt-4">
            {notice}
          </InlineAlert>
        )}
      </div>

      {error && (
        <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
          {error}
        </InlineAlert>
      )}

      {status === 'ready' && refined && (
        <p className="mt-4 text-sm text-white/40">
          Showing {reports(visible.length)} of the {loaded.length} loaded so far.
          {cursor !== null && ' There are more pages; load them to narrow over those too.'}
        </p>
      )}

      {status === 'loading' && (
        <SkeletonGroup label="Loading the moderation queue" className="mt-4">
          <div className="space-y-4">
            {[0, 1, 2].map((row) => (
              <div key={row} className="rounded-lg border border-white/8 bg-surface-2 p-5">
                <Skeleton height="1.125rem" width="35%" />
                <Skeleton height="0.875rem" width="55%" className="mt-3" />
                <Skeleton height="0.875rem" width="80%" className="mt-2" />
              </div>
            ))}
          </div>
        </SkeletonGroup>
      )}

      {status === 'ready' && visible.length === 0 && (
        <EmptyState
          className="mt-4"
          variant={refined || loaded.length > 0 ? 'filtered' : 'empty'}
          title={
            refined
              ? 'Nothing matches these filters'
              : state === 'OPEN'
                ? 'The queue is clear'
                : `No ${STATE_LABELS[state].toLowerCase()} reports`
          }
          description={
            refined
              ? 'Widen the filters, or load another page — the last two narrow what is already loaded rather than asking the service again.'
              : state === 'OPEN'
                ? 'Every report has been decided. New complaints arrive here as they are made.'
                : 'Nothing has been decided this way yet.'
          }
        />
      )}

      {status === 'ready' && visible.length > 0 && (
        <ul className="mt-4 space-y-4">
          {visible.map((report) => (
            <ReportCard
              key={report.id}
              report={report}
              now={now}
              busy={busyIds.has(report.id)}
              detailHref={
                detailHrefBase === undefined
                  ? undefined
                  : `${detailHrefBase}/${encodeURIComponent(report.id)}`
              }
              onDecide={openDialog}
            />
          ))}
        </ul>
      )}

      {status === 'ready' && cursor !== null && (
        <Pill
          variant="ghost"
          size="sm"
          className="mt-4"
          disabled={loadingMore}
          onClick={() => void loadMore()}
        >
          {loadingMore ? 'Loading' : 'Load more'}
        </Pill>
      )}

      {status === 'failed' && (
        <Pill variant="ghost" size="sm" className="mt-4" onClick={() => setAttempt((n) => n + 1)}>
          Try again
        </Pill>
      )}

      <DecisionDialog
        decision={pending?.decision ?? null}
        report={pending?.report ?? null}
        busy={dialogBusy}
        error={dialogError}
        onCancel={closeDialog}
        onConfirm={(note) => void commit(note)}
      />
    </section>
  );
}
