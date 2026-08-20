'use client';

import { useCallback, useEffect, useState } from 'react';
import { Download, Search, X } from 'lucide-react';
import { Chip, Field, InlineAlert, Skeleton, SkeletonGroup, TextInput } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import {
  deleteSegment,
  exportBackers,
  isNarrowed,
  listBackers,
  listSegments,
  NO_FILTER,
  REPORTED_STATES,
  saveSegment,
  type BackerFilter,
  type BackerPage,
  type BackerSegment,
  type ReportedState,
} from '../../lib/dashboard/backers';
import { BackerTable } from './BackerTable';

/**
 * §4.7's CD-10 and CD-11: the backer report, its saved segments, and its export.
 *
 * <h2>Why the filter is state and the segment is an identifier</h2>
 *
 * Applying a saved segment does not copy its filter into the controls. It sends the
 * segment's identifier and lets the service resolve it, because a segment is a question
 * whose answer moves — a copy taken at click time would go stale the moment somebody
 * edited the segment in another tab, and the export would then produce a different set
 * from the screen it was taken from.
 *
 * <h2>The count is the service's, always</h2>
 *
 * `matched` comes back with every page and describes the campaign rather than the page. A
 * creator about to save a segment for a bulk message is entitled to know how many people
 * it reaches, and a number derived from the rows on screen would understate it by exactly
 * the amount that matters.
 *
 * <h2>Motion</h2>
 *
 * None. docs/motion-system.md §5 gives the dashboard the smallest budget but one, and this
 * is a working tool a creator scrolls and searches. The loading state is a skeleton, which
 * is the one animation that budget sanctions, and it comes from `@ideanest/ui` rather than
 * from `@ideanest/ui/motion` — the animated exports carry 116 kB of runtime that this route
 * has no use for.
 */

type Status = 'loading' | 'ready' | 'failed';

/** What a refusal means, branched on status rather than on the service's prose. */
function messageFor(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 401) return 'Your session has expired. Sign in again to see this campaign.';
    if (cause.status === 403) {
      return 'Your collaborator grant on this campaign does not include the backer report.';
    }
    if (cause.status === 404) return 'That campaign does not exist, or it is not one you work on.';
    if (cause.status === 429) {
      return 'That is more exports than this account may take in a minute. Try again shortly.';
    }
  }
  return 'The backer report could not be loaded. It is the service rather than your campaign — try again shortly.';
}

/** What a refused save means. 409 is the only one a creator can act on directly. */
function saveMessageFor(cause: unknown): string {
  if (cause instanceof ApiError && cause.status === 409) {
    return 'This campaign already has a segment by that name, or has as many as the report holds.';
  }
  return 'That segment could not be saved. Try again shortly.';
}

export interface BackerReportProps {
  readonly projectId: string;
  /** Injected by tests. Default to the real readers. */
  readonly load?: typeof listBackers;
  readonly loadSegments?: typeof listSegments;
  readonly save?: typeof saveSegment;
  readonly remove?: typeof deleteSegment;
  readonly download?: typeof exportBackers;
  /** Injected by tests: how a file is offered. Defaults to an object URL and a click. */
  readonly offerFile?: (filename: string, csv: string) => void;
}

export function BackerReport({
  projectId,
  load,
  loadSegments,
  save,
  remove,
  download,
  offerFile,
}: BackerReportProps) {
  const [status, setStatus] = useState<Status>('loading');
  const [page, setPage] = useState<BackerPage | null>(null);
  const [failure, setFailure] = useState('');
  const [filter, setFilter] = useState<BackerFilter>(NO_FILTER);
  const [term, setTerm] = useState('');
  const [segmentId, setSegmentId] = useState<string | undefined>(undefined);
  const [segments, setSegments] = useState<readonly BackerSegment[]>([]);
  const [segmentName, setSegmentName] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const reader = load ?? listBackers;
  const segmentReader = loadSegments ?? listSegments;

  useEffect(() => {
    const controller = new AbortController();
    setStatus('loading');

    reader(projectId, { filter, segmentId, signal: controller.signal })
      .then((body) => {
        setPage(body);
        setStatus('ready');
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setFailure(messageFor(cause));
        setStatus('failed');
      });

    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, filter, segmentId]);

  useEffect(() => {
    const controller = new AbortController();
    segmentReader(projectId, controller.signal)
      .then(setSegments)
      // A campaign with no readable segments is a report with no chips, which is a
      // smaller failure than the report itself and must not replace it with an error.
      .catch(() => undefined);
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  const applyFilter = useCallback((next: BackerFilter) => {
    // Choosing a control clears the segment: the two are alternatives, and leaving a
    // segment selected while its chips disagreed with the controls would show a creator a
    // filter that is not the one being run.
    setSegmentId(undefined);
    setFilter(next);
  }, []);

  const toggleState = (state: ReportedState) => {
    const states = filter.states.includes(state)
      ? filter.states.filter((each) => each !== state)
      : [...filter.states, state];
    applyFilter({ ...filter, states });
  };

  const search = (event: React.FormEvent) => {
    event.preventDefault();
    applyFilter({ ...filter, term });
  };

  const onSave = async (event: React.FormEvent) => {
    event.preventDefault();
    if (segmentName.trim() === '') return;

    setBusy(true);
    setNotice('');
    try {
      const saved = await (save ?? saveSegment)(projectId, segmentName.trim(), filter);
      setSegments([saved, ...segments]);
      setSegmentName('');
      setNotice(`Saved “${saved.name}”.`);
    } catch (cause) {
      setNotice(saveMessageFor(cause));
    } finally {
      setBusy(false);
    }
  };

  const onDelete = async (segment: BackerSegment) => {
    setBusy(true);
    try {
      await (remove ?? deleteSegment)(projectId, segment.id);
      setSegments(segments.filter((each) => each.id !== segment.id));
      if (segmentId === segment.id) setSegmentId(undefined);
      setNotice(`Deleted “${segment.name}”.`);
    } catch {
      setNotice('That segment could not be deleted. Try again shortly.');
    } finally {
      setBusy(false);
    }
  };

  const onExport = async () => {
    setBusy(true);
    setNotice('');
    try {
      const file = await (download ?? exportBackers)(projectId, { filter, segmentId });
      (offerFile ?? offerDownload)(file.filename, file.csv);
      setNotice(
        file.truncated
          ? `Exported the first ${file.rows} backers. This campaign has more than one file holds — filter it and export the parts.`
          : `Exported ${file.rows} ${file.rows === 1 ? 'backer' : 'backers'}.`,
      );
    } catch (cause) {
      setNotice(messageFor(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="backers-heading">
      <h1 id="backers-heading" className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Backers
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        Everybody who has backed this campaign, and how to reach them. The export carries the
        same people as the list below it.
      </p>

      <form onSubmit={search} className="mt-6 flex flex-wrap items-end gap-3">
        <div className="min-w-[240px] flex-1">
          <Field label="Search backers" hint="A name or an email address, or part of one.">
            <TextInput value={term} onChange={(event) => setTerm(event.target.value)} />
          </Field>
        </div>
        <button
          type="submit"
          className="inline-flex items-center gap-2 rounded-full border border-white/16 px-4 py-2.5 text-sm font-medium text-white hover:bg-[--surface-3] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[--lime-500]"
        >
          <Search className="size-4" aria-hidden />
          Search
        </button>
        <button
          type="button"
          onClick={onExport}
          disabled={busy}
          className="inline-flex items-center gap-2 rounded-full border border-white/16 px-4 py-2.5 text-sm font-medium text-white hover:bg-[--surface-3] disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[--lime-500]"
        >
          <Download className="size-4" aria-hidden />
          Export CSV
        </button>
      </form>

      <fieldset className="mt-6">
        <legend className="text-sm font-medium text-white">Pledge state</legend>
        <div className="mt-3 flex flex-wrap gap-2">
          {REPORTED_STATES.map((state) => {
            const on = filter.states.includes(state);
            return (
              <Chip key={state} active={on} onClick={() => toggleState(state)}>
                {STATE_LABELS[state]}
              </Chip>
            );
          })}
        </div>
      </fieldset>

      {segments.length > 0 ? (
        <fieldset className="mt-6">
          <legend className="text-sm font-medium text-white">Saved segments</legend>
          <div className="mt-3 flex flex-wrap gap-2">
            {segments.map((segment) => (
              <span key={segment.id} className="inline-flex items-center gap-1">
                <Chip
                  active={segmentId === segment.id}
                  onClick={() => {
                    // Selecting a segment clears the loose controls for the reason
                    // applyFilter clears the segment: only one of the two is being run.
                    setFilter(NO_FILTER);
                    setTerm('');
                    setSegmentId(segmentId === segment.id ? undefined : segment.id);
                  }}
                >
                  {segment.name}
                </Chip>
                <button
                  type="button"
                  onClick={() => onDelete(segment)}
                  disabled={busy}
                  aria-label={`Delete the segment ${segment.name}`}
                  className="rounded-full p-1 text-white/64 hover:text-white disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[--lime-500]"
                >
                  <X className="size-4" aria-hidden />
                </button>
              </span>
            ))}
          </div>
        </fieldset>
      ) : null}

      {isNarrowed(filter) ? (
        <form onSubmit={onSave} className="mt-6 flex flex-wrap items-end gap-3">
          <div className="min-w-[240px] flex-1">
            <Field label="Save this filter as" hint="Eighty characters or fewer.">
              <TextInput
                value={segmentName}
                onChange={(event) => setSegmentName(event.target.value)}
                placeholder="Our German backers"
                maxLength={80}
              />
            </Field>
          </div>
          <button
            type="submit"
            disabled={busy || segmentName.trim() === ''}
            className="inline-flex items-center gap-2 rounded-full bg-[--lime-500] px-4 py-2.5 text-sm font-semibold text-[--text-on-lime] hover:bg-[--lime-400] disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[--lime-500]"
          >
            Save segment
          </button>
        </form>
      ) : null}

      {/* Both a save and an export report through here, so the two cannot argue about
          which owns the space under the controls. Polite rather than assertive: none of
          this is urgent enough to interrupt what a screen reader is saying. */}
      <p aria-live="polite" className="mt-4 min-h-[1.25rem] text-sm text-white/64">
        {notice}
      </p>

      {status === 'loading' ? (
        <SkeletonGroup label="Loading this campaign's backers">
          <Skeleton className="h-6 w-1/3" />
          <Skeleton className="h-40 w-full" />
        </SkeletonGroup>
      ) : null}

      {status === 'failed' ? <InlineAlert variant="danger">{failure}</InlineAlert> : null}

      {status === 'ready' && page !== null ? (
        <>
          <p className="mt-6 text-sm text-white">
            <span className="tabular-nums">{page.matched}</span>{' '}
            {page.matched === 1 ? 'backer' : 'backers'} match this filter.
          </p>

          {page.backers.length === 0 ? (
            <p className="mt-4 max-w-[62ch] text-sm text-white/64">
              {isNarrowed(filter) || segmentId !== undefined
                ? 'Nothing matches this filter. Clear a chip or widen the search.'
                : 'Nobody has backed this campaign yet. Backers appear here as soon as they confirm.'}
            </p>
          ) : (
            <BackerTable backers={page.backers} label="This campaign's backers" />
          )}

          {page.nextCursor !== undefined ? (
            // Deliberately not a "load more" that appends. The list is a report a creator
            // reads and exports rather than a feed they scroll, and #79's file is what
            // answers "I want all of them" — a browser holding forty thousand rows in
            // memory is the wrong tool for the same question.
            <p className="mt-4 text-sm text-white/64">
              Showing the most recent {page.backers.length}. Narrow the filter, or export the
              file, to see the rest.
            </p>
          ) : null}
        </>
      ) : null}
    </section>
  );
}

/** The five states in the words a creator uses, matching the table's own labels. */
const STATE_LABELS: Record<ReportedState, string> = {
  CONFIRMED: 'Confirmed',
  CHARGE_PENDING: 'Awaiting collection',
  CHARGE_FAILED: 'Payment failed',
  COLLECTED: 'Collected',
  FULFILLED: 'Fulfilled',
};

/**
 * Hands the file to the browser.
 *
 * An object URL and a synthetic click, because the export is a POST and a POST cannot be a
 * link. The URL is revoked immediately after: it holds the whole file — a campaign's
 * mailing list — alive in the tab for as long as it exists.
 */
function offerDownload(filename: string, csv: string): void {
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
