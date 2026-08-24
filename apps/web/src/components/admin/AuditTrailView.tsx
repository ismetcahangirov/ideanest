'use client';

import { useCallback, useEffect, useState } from 'react';
import { Chip, ChipRow, EmptyState, InlineAlert, Pill, Skeleton, SkeletonGroup, Tag } from '@ideanest/ui';
import {
  AUDIT_ENTITY_TYPES,
  TRAIL_PAGE_SIZE,
  actionLabel,
  readTrail,
  type AuditEntry,
} from '../../lib/admin/audit';
import {
  consoleMessageFor,
  shortId,
  statusFor,
  wasAborted,
  type ConsoleStatus,
} from '../../lib/admin/refusals';
import { ConsoleRefusal } from './ConsoleRefusal';

const SUBJECT = 'the audit trail';

/**
 * §4.11's AD-14: what has been done, by whom, to what — issue #314.
 *
 * <h2>Newest first, where the report queue is oldest first</h2>
 *
 * Not a preference. A queue is worked from the front — the complaint that has waited longest
 * is the one that matters — and a trail is read from the end: "what has just happened", "what
 * did that account do before it was stopped". The row somebody wants is almost always the
 * most recent one that matches.
 *
 * <h2>Three filters, and the reason there are not more</h2>
 *
 * The service narrows by entity kind, by one entity, or by one actor, because those are the
 * three indexes V21 created. There is no filter on the action, no date range and no search
 * over the free text, and this screen does not offer any of them client-side either — a chip
 * that filtered the twenty-five rows in the browser would say "3 results" about a table with
 * four thousand matching rows in it, which on an audit surface is not a rough edge but a
 * wrong answer. `lib/admin/audit.ts` records what each missing filter would cost.
 *
 * <h2>Nothing here can change anything</h2>
 *
 * There is no action on any row, because there is no endpoint that could take one: V21 puts a
 * trigger on `audit_logs` that raises on UPDATE, DELETE and TRUNCATE. That is the property
 * that makes the screen worth reading, and it is why this is the one console screen with no
 * confirmation dialog in it.
 *
 * <h2>Reading it is itself recorded</h2>
 *
 * Every page drawn here writes a row to the table being drawn. That is intended: an audit
 * surface nobody audits is the surface an investigation starts by distrusting. The screen
 * says so, because a reader who saw their own reads appear and had not been told would think
 * something was wrong.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 gives an administrative surface no budget beyond 150ms of colour
 * on a control, and §8 forbids animation in long lists regardless. This is both.
 */
export function AuditTrailView() {
  const [status, setStatus] = useState<ConsoleStatus>('loading');
  const [entityType, setEntityType] = useState<string | null>(null);
  const [entries, setEntries] = useState<readonly AuditEntry[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();

    async function load(): Promise<void> {
      setStatus('loading');
      try {
        const page = await readTrail({
          entityType,
          limit: TRAIL_PAGE_SIZE,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;

        setEntries(page.entries);
        setCursor(page.nextCursor ?? null);
        setError(null);
        setStatus('ready');
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;

        const next = statusFor(cause);
        if (next === 'failed') setError(consoleMessageFor(cause, SUBJECT));
        setStatus(next);
      }
    }

    void load();
    return () => controller.abort();
  }, [entityType, attempt]);

  const loadMore = useCallback(async (): Promise<void> => {
    if (cursor === null || loadingMore) return;

    setLoadingMore(true);
    setError(null);
    try {
      const page = await readTrail({ entityType, after: cursor, limit: TRAIL_PAGE_SIZE });
      setEntries((previous) => [...previous, ...page.entries]);
      setCursor(page.nextCursor ?? null);
    } catch (cause) {
      setError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, entityType, loadingMore]);

  if (status === 'signed-out' || status === 'forbidden') {
    return <ConsoleRefusal status={status} subject={SUBJECT} />;
  }

  return (
    <section aria-labelledby="audit-trail-heading">
      <h2
        id="audit-trail-heading"
        className="text-lg font-medium tracking-[-0.02em] text-white"
      >
        Privileged actions
        {status === 'ready' && (
          <span className="ml-2 text-xs font-normal text-white/40">{entries.length}</span>
        )}
      </h2>

      {/*
        Every chip here reaches the service. There is deliberately no client-side narrowing
        on this screen — see the docblock on why a count that means "of the page" is a wrong
        answer on an audit surface rather than a rough edge.
      */}
      <ChipRow aria-label="What the action was about" className="mt-4">
        <Chip active={entityType === null} onClick={() => setEntityType(null)}>
          Everything
        </Chip>
        {AUDIT_ENTITY_TYPES.map(([value, label]) => (
          <Chip key={value} active={entityType === value} onClick={() => setEntityType(value)}>
            {label}
          </Chip>
        ))}
      </ChipRow>

      {error && (
        <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
          {error}
        </InlineAlert>
      )}

      {status === 'loading' && (
        <SkeletonGroup label="Loading the audit trail" className="mt-4">
          <div className="space-y-3">
            {[0, 1, 2, 3].map((row) => (
              <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                <Skeleton height="1rem" width="30%" />
                <Skeleton height="0.875rem" width="60%" className="mt-3" />
              </div>
            ))}
          </div>
        </SkeletonGroup>
      )}

      {status === 'ready' && entries.length === 0 && (
        <EmptyState
          className="mt-4"
          variant={entityType === null ? 'empty' : 'filtered'}
          title={entityType === null ? 'Nothing has been recorded yet' : 'Nothing of that kind'}
          description={
            entityType === null
              ? 'Every privileged action writes a row here. An empty trail means none has been taken on this deployment.'
              : 'No privileged action has been recorded about that kind of thing. Widen the filter to see the rest.'
          }
        />
      )}

      {status === 'ready' && entries.length > 0 && (
        <ul className="mt-4 flex list-none flex-col gap-2">
          {entries.map((entry) => (
            <AuditRow key={entry.id} entry={entry} />
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

      <p className="mt-8 max-w-[68ch] text-sm text-white/40">
        Reading this page writes a row to it. An audit surface nobody audits is the one an
        investigation starts by distrusting, so a read is recorded like a write — with the
        filter and the number of rows, and never the rows themselves.
      </p>
    </section>
  );
}

/**
 * One recorded action.
 *
 * <p><strong>A refusal is drawn as prominently as a success</strong>, in `warning` rather
 * than in `danger`. `REFUSED` means somebody tried something they were not entitled to and
 * the platform said no — which is the trail working, not the platform failing — and it is
 * also the single most interesting row on this screen. Red would say the system broke; the
 * word says what actually happened, and docs/ui-kit.md §9.2 requires the word either way.
 *
 * <p>The identifiers are shortened and the full value is on the element's `title`, because a
 * list of forty rows each carrying three UUIDs is a list nobody can scan — and the full value
 * is what somebody copies into the next query.
 */
function AuditRow({ entry }: { readonly entry: AuditEntry }) {
  return (
    <li className="rounded-lg border border-white/8 bg-surface-1 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[15px] font-medium text-white">{actionLabel(entry.action)}</p>
          <p className="mt-1 text-sm text-white/64">
            {entry.entityType}{' '}
            <span className="font-mono" title={entry.entityId}>
              {shortId(entry.entityId)}
            </span>
            {' · '}
            {entry.actorType.toLowerCase()}
            {entry.actorId != null && (
              <>
                {' '}
                <span className="font-mono" title={entry.actorId}>
                  {shortId(entry.actorId)}
                </span>
              </>
            )}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
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

      {/*
        The correlation identifier, which is the only thing on this row that reaches outside
        the database: §18.1 stamps the same value on the log lines for the request, so this is
        what turns "the ban was recorded" into "here is everything that happened while it was".
      */}
      {entry.requestId != null && entry.requestId !== '' && (
        <p className="mt-2 font-mono text-xs text-white/32">request {entry.requestId}</p>
      )}
    </li>
  );
}
