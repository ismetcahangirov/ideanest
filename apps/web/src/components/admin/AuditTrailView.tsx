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
  requiredCapabilityFrom,
  shortId,
  statusFor,
  wasAborted,
  type ConsoleStatus,
} from '../../lib/admin/refusals';
import { fillPlaceholders } from '../../lib/i18n/placeholders';
import type { AuditTrailCopy } from '../../lib/i18n/admin/platform-copy';
import { useRouteLocale } from '../../lib/i18n/useRouteLocale';
import { formatExactTime } from '../../lib/time';
import type { Locale } from '../../lib/i18n/locale';
import { ConsoleRefusal } from './ConsoleRefusal';

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
 *
 * <h2>Every word arrives as a prop</h2>
 *
 * Resolved by the route on the server and handed down, since #324. The two label tables come
 * with it: `lib/admin/audit.ts` used to carry the English for each entity kind and each action
 * beside the list of which ones exist, and the words moved to `admin.screens.audit` while the
 * list stayed where it was.
 */
export interface AuditTrailViewProps {
  readonly copy: AuditTrailCopy;
}

export function AuditTrailView({ copy }: AuditTrailViewProps) {
  const locale = useRouteLocale();
  const [status, setStatus] = useState<ConsoleStatus>('loading');
  // #400: which of the two 403s this is. Only read while `status` is `forbidden`.
  const [capability, setCapability] = useState<string | null>(null);
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
        setCapability(requiredCapabilityFrom(cause));
        if (next === 'failed') setError(consoleMessageFor(cause, copy.subject, copy.refusals));
        setStatus(next);
      }
    }

    void load();
    return () => controller.abort();
    // `copy` is not a dependency: it is one object per server render, so it changes only when
    // the language does, and the language is a path segment that remounts this tree.
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, entityType, loadingMore, copy]);

  if (status === 'signed-out' || status === 'forbidden') {
    return <ConsoleRefusal status={status} capability={capability} subject={copy.subject} copy={copy.refusals} />;
  }

  return (
    <section aria-labelledby="audit-trail-heading">
      <h2
        id="audit-trail-heading"
        className="text-lg font-medium tracking-[-0.02em] text-white"
      >
        {copy.heading}
        {status === 'ready' && (
          <span className="ml-2 text-xs font-normal text-white/40">{entries.length}</span>
        )}
      </h2>

      {/*
        Every chip here reaches the service. There is deliberately no client-side narrowing
        on this screen — see the docblock on why a count that means "of the page" is a wrong
        answer on an audit surface rather than a rough edge.
      */}
      <ChipRow aria-label={copy.filterLabel} className="mt-4">
        <Chip active={entityType === null} onClick={() => setEntityType(null)}>
          {copy.everything}
        </Chip>
        {AUDIT_ENTITY_TYPES.map((value) => (
          <Chip key={value} active={entityType === value} onClick={() => setEntityType(value)}>
            {copy.entity[value] ?? value}
          </Chip>
        ))}
      </ChipRow>

      {error && (
        <InlineAlert variant="danger" title={copy.errorTitle} className="mt-4">
          {error}
        </InlineAlert>
      )}

      {status === 'loading' && (
        <SkeletonGroup label={copy.loadingList} className="mt-4">
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
          title={entityType === null ? copy.emptyTitle : copy.filteredTitle}
          description={entityType === null ? copy.emptyBody : copy.filteredBody}
        />
      )}

      {status === 'ready' && entries.length > 0 && (
        <ul className="mt-4 flex list-none flex-col gap-2">
          {entries.map((entry) => (
            <AuditRow key={entry.id} entry={entry} locale={locale} copy={copy} />
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
          {loadingMore ? copy.loading : copy.loadMore}
        </Pill>
      )}

      {status === 'failed' && (
        <Pill variant="ghost" size="sm" className="mt-4" onClick={() => setAttempt((n) => n + 1)}>
          {copy.tryAgain}
        </Pill>
      )}

      <p className="mt-8 max-w-[68ch] text-sm text-white/40">{copy.footnote}</p>
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
function AuditRow({
  entry,
  locale,
  copy,
}: {
  readonly entry: AuditEntry;
  readonly locale: Locale;
  readonly copy: AuditTrailCopy;
}) {
  return (
    <li className="rounded-lg border border-white/8 bg-surface-1 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[15px] font-medium text-white">{actionLabel(entry.action, copy.action)}</p>
          <p className="mt-1 text-sm text-white/64">
            {copy.entity[entry.entityType] ?? entry.entityType}{' '}
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
          {entry.outcome === 'REFUSED' && <Tag variant="warning">{copy.refused}</Tag>}
          <time
            dateTime={entry.occurredAt}
            className="text-xs text-white/40"
            title={entry.occurredAt}
          >
            {formatExactTime(entry.occurredAt, locale)}
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
        <p className="mt-2 font-mono text-xs text-white/32">
          {fillPlaceholders(copy.requestId, { id: entry.requestId })}
        </p>
      )}
    </li>
  );
}
