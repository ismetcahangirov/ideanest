'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Chip,
  ChipRow,
  EmptyState,
  Field,
  InlineAlert,
  Pill,
  Skeleton,
  SkeletonGroup,
  Tag,
  TextInput,
} from '@ideanest/ui';
import {
  AUDIT_ENTITY_TYPES,
  TRAIL_PAGE_SIZE,
  actionLabel,
  dayBounds,
  readTrail,
  type AuditEntry,
} from '../../lib/admin/audit';
import type { AdminUser } from '../../lib/admin/api';
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
import type { DirectoryNames } from '../../lib/admin/directory';
import { AccountPicker } from './AccountPicker';
import { ConsoleCount } from './ConsoleCount';
import { ConsoleRefusal } from './ConsoleRefusal';
import { EntityName } from './ConsoleIdentity';
import { useDirectoryNames } from './useDirectoryNames';

/**
 * §4.11's AD-14: what has been done, by whom, to what — issue #314.
 *
 * <h2>Newest first, where the report queue is oldest first</h2>
 *
 * <p><strong>And it now is — issue #404.</strong> This heading was true of the intent and
 * false of the page. The service ordered by the primary key, on the argument that a UUID v7
 * carries the millisecond it was minted in (§7.3); the key is minted by the application and
 * `occurred_at` is `DEFAULT now()`, so the two disagree, and this screen displays the second
 * while the query ordered by the first. Walked against the local seed, the first fourteen
 * rows were from the previous month and that morning's eight privileged actions began at
 * position fifteen. An investigator who opens the log and sees August at the top has no
 * reason to scroll for this morning.
 *
 * Not a preference. A queue is worked from the front — the complaint that has waited longest
 * is the one that matters — and a trail is read from the end: "what has just happened", "what
 * did that account do before it was stopped". The row somebody wants is almost always the
 * most recent one that matches.
 *
 * <h2>Four filters, and the reason there are not more</h2>
 *
 * The service narrows by entity kind, by one entity, or by one actor, because those are the
 * three indexes V21 created — and, since #404, by a date range, which is the one filter that
 * costs nothing because `occurred_at DESC` is the trailing column of all four of those
 * indexes.
 *
 * That issue is also why the actor filter has a control at all. The service had accepted
 * `actorId` since #314 and the screen offered no way to set one, so "what did this person do"
 * — the question an audit log exists to answer — was reachable only by editing the URL.
 * `AccountPicker` is the control, for the reason it was built: an identifier a moderator has
 * to already hold is not a filter they can use.
 *
 * There is still no filter on the action and no search over the free text, and this screen
 * does not offer either client-side — a chip that filtered the twenty-five loaded rows in the
 * browser would say "3 results" about a table with four thousand matching rows in it, which
 * on an audit surface is not a rough edge but a wrong answer. `lib/admin/audit.ts` records
 * what each missing filter would cost.
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
  /*
   * #404: who, and when. Both reach the service.
   *
   * `actor` holds the whole account rather than its identifier, because that is what
   * `AccountPicker` hands back and what lets the applied filter be rendered as a person
   * instead of thirty-six characters — on the one screen whose purpose is that somebody who
   * was not there can read what happened.
   *
   * The two dates are the reader's own days, `YYYY-MM-DD` as an `<input type="date">`
   * produces them. `dayBounds` turns each into the instant the service wants, in the
   * browser's timezone, which is the only definition of "last Tuesday" a moderator would
   * recognise.
   */
  const [actor, setActor] = useState<AdminUser | null>(null);
  const [fromDay, setFromDay] = useState('');
  const [toDay, setToDay] = useState('');
  const [entries, setEntries] = useState<readonly AuditEntry[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  /* The effect depends on who, not on the object holding them: `AccountPicker` hands back a
     fresh record each time it searches, and depending on the object would re-read the trail
     when somebody picked the same person twice. */
  const actorId = actor?.id ?? null;

  /** Whether the reader has narrowed the trail at all, which decides what an empty page says. */
  const narrowed = entityType !== null || actorId !== null || fromDay !== '' || toDay !== '';

  useEffect(() => {
    const controller = new AbortController();

    async function load(): Promise<void> {
      setStatus('loading');
      try {
        const page = await readTrail({
          entityType,
          actorId,
          from: dayBounds(fromDay, 'from'),
          to: dayBounds(toDay, 'to'),
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
  }, [entityType, actorId, fromDay, toDay, attempt]);

  const loadMore = useCallback(async (): Promise<void> => {
    if (cursor === null || loadingMore) return;

    setLoadingMore(true);
    setError(null);
    try {
      const page = await readTrail({
        entityType,
        actorId,
        from: dayBounds(fromDay, 'from'),
        to: dayBounds(toDay, 'to'),
        after: cursor,
        limit: TRAIL_PAGE_SIZE,
      });
      setEntries((previous) => [...previous, ...page.entries]);
      setCursor(page.nextCursor ?? null);
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, entityType, actorId, fromDay, toDay, loadingMore, copy]);

  /*
   * Who did it and, where the platform can say, what it was done to — #402. An audit trail
   * exists to be read after the fact by somebody who was not there, and "moderator 4ae450ba
   * suspended account c8edac99" is a sentence that cannot be read at all.
   */
  const names = useDirectoryNames(
    entries.flatMap((entry) => [
      entry.actorId ?? null,
      entry.entityType === 'account' ? entry.entityId : null,
    ]).filter((id): id is string => id != null),
    entries.filter((entry) => entry.entityType === 'project').map((entry) => entry.entityId),
  );

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
          <ConsoleCount loaded={entries.length} more={cursor !== null} copy={copy.count} />
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

      {/*
        #404: who, and when.

        Not a form with a submit button, unlike the account directory's search. Nothing here
        is typed — a day comes from a date picker and an actor from `AccountPicker`, which has
        its own search and its own submit — so there is nothing to hold back until an
        intention is complete. Each control produces one read when it changes.

        `fieldset` rather than three loose fields, so that a screen reader announces the group
        before its parts: "when" is what the two dates mean together, and either alone is an
        open-ended range rather than half a filter.
      */}
      <fieldset className="mt-4">
        <legend className="text-sm text-white/64">{copy.narrowLabel}</legend>

        <div className="mt-2 flex flex-wrap items-end gap-3">
          <AccountPicker
            chosen={actor}
            onChoose={setActor}
            copy={copy.actorPicker}
            className="min-w-[280px] flex-1"
          />

          {/*
            `type="date"` rather than a text field. The format a person types differs by
            language — §21.1 puts the console in four — and the browser's own control renders
            the reader's format over a value that is always `YYYY-MM-DD`.
          */}
          <Field label={copy.fromLabel} hint={copy.fromHint} className="min-w-[160px]">
            <TextInput
              type="date"
              value={fromDay}
              max={toDay === '' ? undefined : toDay}
              onChange={(event) => setFromDay(event.target.value)}
            />
          </Field>

          <Field label={copy.toLabel} hint={copy.toHint} className="min-w-[160px]">
            <TextInput
              type="date"
              value={toDay}
              min={fromDay === '' ? undefined : fromDay}
              onChange={(event) => setToDay(event.target.value)}
            />
          </Field>

          {(fromDay !== '' || toDay !== '') && (
            <Pill
              variant="ghost"
              size="sm"
              className="mb-1"
              onClick={() => {
                setFromDay('');
                setToDay('');
              }}
            >
              {copy.clearDates}
            </Pill>
          )}
        </div>
      </fieldset>

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
          /* Any of the four narrows the trail, so an empty page under one of them is
             "nothing matched" rather than "nothing has ever happened" — #404. */
          variant={narrowed ? 'filtered' : 'empty'}
          title={narrowed ? copy.filteredTitle : copy.emptyTitle}
          description={narrowed ? copy.filteredBody : copy.emptyBody}
        />
      )}

      {status === 'ready' && entries.length > 0 && (
        <ul className="mt-4 flex list-none flex-col gap-2">
          {entries.map((entry) => (
            <AuditRow key={entry.id} entry={entry} locale={locale} names={names} copy={copy} />
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
  names,
  copy,
}: {
  readonly entry: AuditEntry;
  readonly locale: Locale;
  readonly names: DirectoryNames;
  readonly copy: AuditTrailCopy;
}) {
  return (
    <li className="rounded-lg border border-white/8 bg-surface-1 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[15px] font-medium text-white">{actionLabel(entry.action, copy.action)}</p>
          <p className="mt-1 text-sm text-white/64">
            {copy.entity[entry.entityType] ?? entry.entityType}{' '}
            {/*
              #402: an audit row's whole purpose is accountability, and "who" and "what"
              were both eight hexadecimal characters. Two of the six entity kinds are things
              the directory can name — a campaign and an account — and the rest keep the
              fragment, because a session or a collection is not a thing with a name.
            */}
            {entry.entityType === 'project' || entry.entityType === 'account' ? (
              <EntityName
                id={entry.entityId}
                names={names}
                kind={entry.entityType === 'project' ? 'project' : 'account'}
                copy={copy.identity}
              />
            ) : (
              <span className="font-mono" title={entry.entityId}>
                {shortId(entry.entityId)}
              </span>
            )}
            {' · '}
            {entry.actorType.toLowerCase()}
            {entry.actorId != null && (
              <>
                {' '}
                <EntityName
                  id={entry.actorId}
                  names={names}
                  kind="account"
                  copy={copy.identity}
                />
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
