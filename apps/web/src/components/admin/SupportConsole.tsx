'use client';

import { useState } from 'react';
import {
  Checkbox,
  EmptyState,
  Field,
  InlineAlert,
  Pill,
  Select,
  Skeleton,
  SkeletonGroup,
  Tag,
  Textarea,
} from '@ideanest/ui';
import {
  TICKET_PRIORITIES,
  TICKET_STATES,
  readTicket,
  readTicketQueue,
  replyToTicket,
  updateTicket,
  type Ticket,
  type TicketPriority,
  type TicketState,
} from '../../lib/admin/tickets';
import { consoleMessageFor, shortId } from '../../lib/admin/refusals';
import { pluralise } from '../../lib/i18n/plurals';
import { useRouteLocale } from '../../lib/i18n/useRouteLocale';
import type { SupportConsoleCopy } from '../../lib/i18n/admin/people-copy';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

/**
 * §4.11's AD-10: tickets with user context and action history — issue #310.
 *
 * <h2>The context is why this is not a mailbox</h2>
 *
 * A support conversation about a pledge is read beside that pledge, the account&apos;s
 * standing, and every other ticket the same person has raised. An email client knows none of
 * those, which is what #310 was actually about — the store was missing because the screen
 * needed a shape a mailbox cannot have.
 *
 * So opening a ticket shows three things at once: the ticket, its thread, and the rest of that
 * person&apos;s history. The fifth complaint from one account is a different conversation from
 * the first, and nobody should have to go and look for that.
 *
 * <h2>An internal note does not move the ticket</h2>
 *
 * Staff talking to staff leaves the requester still waiting, so the ticket stays where it is.
 * Everything else about a reply does move it, in the entity rather than here — a reply that did
 * not take the ticket off the queue is a reply the next person has to notice by reading it.
 */
export interface SupportConsoleProps {
  readonly copy: SupportConsoleCopy;
}

export function SupportConsole({ copy }: SupportConsoleProps) {
  const [page, setPage] = useState(0);
  const [openTicket, setOpenTicket] = useState<string | null>(null);

  const queue = useConsoleResource(
    (signal) => readTicketQueue(page, signal),
    copy.subject,
    copy.refusals,
    [page],
  );

  if (queue.status === 'signed-out' || queue.status === 'forbidden') {
    return <ConsoleRefusal status={queue.status} subject={copy.subject} copy={copy.refusals} />;
  }

  return (
    <div className="flex flex-col gap-8">
      <section aria-labelledby="queue-heading">
        <h2 id="queue-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.heading}
          {queue.status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">
              {queue.data?.tickets.length ?? 0}
            </span>
          )}
        </h2>
        <p className="mt-2 max-w-[62ch] text-sm text-white/64">{copy.intro}</p>

        {queue.status === 'loading' && (
          <SkeletonGroup label={copy.loadingList} className="mt-4">
            <div className="space-y-3">
              {[0, 1, 2].map((row) => (
                <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                  <Skeleton height="1rem" width="45%" />
                  <Skeleton height="0.875rem" width="70%" className="mt-3" />
                </div>
              ))}
            </div>
          </SkeletonGroup>
        )}

        {queue.status === 'failed' && (
          <>
            <InlineAlert variant="danger" title={copy.errorTitle} className="mt-4">
              {queue.error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={queue.reload}>
              {copy.tryAgain}
            </Pill>
          </>
        )}

        {queue.status === 'ready' && queue.data !== null && queue.data.tickets.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title={copy.emptyTitle}
            description={copy.emptyBody}
          />
        )}

        {queue.status === 'ready' && queue.data !== null && queue.data.tickets.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-2">
            {queue.data.tickets.map((ticket) => (
              <li key={ticket.id}>
                <button
                  type="button"
                  onClick={() => setOpenTicket(openTicket === ticket.id ? null : ticket.id)}
                  aria-expanded={openTicket === ticket.id}
                  className="w-full rounded-lg border border-white/8 bg-surface-1 p-4 text-left transition-colors duration-150 ease-in-out hover:border-white/16 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                >
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <p className="text-sm text-white">{ticket.subject}</p>
                    <span className="flex items-center gap-2">
                      <Tag>{copy.priority[ticket.priority]}</Tag>
                      <Tag>{copy.state[ticket.state]}</Tag>
                    </span>
                  </div>
                  <p className="mt-2 text-xs text-white/40">
                    <span className="font-mono">{shortId(ticket.requesterId)}</span> ·{' '}
                    {ticket.createdAt.slice(0, 10)}
                    {ticket.assigneeId == null
                      ? ` · ${copy.unassigned}`
                      : ` · ${shortId(ticket.assigneeId)}`}
                  </p>
                </button>

                {openTicket === ticket.id && (
                  <TicketDetail ticketId={ticket.id} copy={copy} onChanged={queue.reload} />
                )}
              </li>
            ))}
          </ul>
        )}

        {queue.status === 'ready' && (page > 0 || (queue.data?.hasMore ?? false)) && (
          <div className="mt-4 flex gap-2">
            <Pill variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((n) => n - 1)}>
              {copy.previous}
            </Pill>
            <Pill
              variant="ghost"
              size="sm"
              disabled={!(queue.data?.hasMore ?? false)}
              onClick={() => setPage((n) => n + 1)}
            >
              {copy.next}
            </Pill>
          </div>
        )}
      </section>
    </div>
  );
}

/** One ticket, its thread, and the rest of the account's history. */
function TicketDetail({
  ticketId,
  copy,
  onChanged,
}: {
  readonly ticketId: string;
  readonly copy: SupportConsoleCopy;
  readonly onChanged: () => void;
}) {
  const locale = useRouteLocale();
  const context = useConsoleResource(
    (signal) => readTicket(ticketId, signal),
    copy.ticketSubject,
    copy.refusals,
    [ticketId],
  );

  const [body, setBody] = useState('');
  const [internal, setInternal] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function act(work: () => Promise<unknown>): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await work();
      context.reload();
      onChanged();
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.ticketSubject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  if (context.status === 'loading') {
    return (
      <SkeletonGroup label={copy.loadingTicket} className="mt-2 rounded-lg border border-white/8 p-4">
        <Skeleton height="0.875rem" width="60%" />
      </SkeletonGroup>
    );
  }

  if (context.status !== 'ready' || context.data === null) {
    return (
      <InlineAlert variant="danger" title={copy.ticketFailedTitle} className="mt-2">
        {context.error ?? copy.tryAgainShort}
      </InlineAlert>
    );
  }

  const { file, history } = context.data;
  const others = history.filter((other) => other.id !== file.ticket.id);

  return (
    <div className="mt-2 rounded-lg border border-white/8 bg-surface-1 p-4">
      <ul className="flex list-none flex-col gap-2">
        {file.messages.map((message) => (
          <li
            key={message.id}
            className={
              message.internal
                ? 'rounded-md border border-dashed border-white/16 p-3'
                : 'rounded-md border border-white/8 p-3'
            }
          >
            <p className="text-xs text-white/40">
              {message.authorSide === 'STAFF' ? copy.staff : copy.requester}
              {message.internal ? ` · ${copy.internalNote}` : ''} ·{' '}
              {message.createdAt.slice(0, 10)}
            </p>
            <p className="mt-1 whitespace-pre-wrap text-sm text-white/80">{message.body}</p>
          </li>
        ))}
      </ul>

      <div className="mt-4 flex flex-col gap-2 border-t border-white/8 pt-4">
        <Field label={copy.replyLabel}>
          <Textarea
            rows={3}
            value={body}
            onChange={(event) => setBody(event.target.value)}
            maxLength={20000}
          />
        </Field>

        <div className="flex flex-wrap items-center gap-3">
          <Checkbox
            checked={internal}
            onChange={(event) => setInternal(event.target.checked)}
            label={copy.internalLabel}
          />
          <Pill
            variant="outline"
            size="sm"
            disabled={busy || body.trim() === ''}
            onClick={() =>
              void act(async () => {
                await replyToTicket(ticketId, body.trim(), internal);
                setBody('');
              })
            }
          >
            {copy.send}
          </Pill>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-end gap-3 border-t border-white/8 pt-4">
        <Field label={copy.priorityLabel} className="min-w-[150px]">
          <Select
            value={file.ticket.priority}
            disabled={busy}
            onChange={(event) =>
              void act(() =>
                updateTicket(ticketId, { priority: event.target.value as TicketPriority }),
              )
            }
          >
            {TICKET_PRIORITIES.map((option) => (
              <option key={option} value={option}>
                {copy.priority[option]}
              </option>
            ))}
          </Select>
        </Field>

        <Field label={copy.stateLabel} className="min-w-[150px]">
          <Select
            value={file.ticket.state}
            disabled={busy}
            onChange={(event) =>
              void act(() => updateTicket(ticketId, { state: event.target.value as TicketState }))
            }
          >
            {TICKET_STATES.map((option) => (
              <option key={option} value={option}>
                {copy.state[option]}
              </option>
            ))}
          </Select>
        </Field>

        <Pill
          variant="ghost"
          size="sm"
          className="mb-1"
          disabled={busy}
          onClick={() => void act(() => updateTicket(ticketId, { unassign: true }))}
        >
          {copy.putBack}
        </Pill>
      </div>

      {others.length > 0 && (
        <div className="mt-4 border-t border-white/8 pt-4">
          <h4 className="text-xs font-medium text-white/64">
            {pluralise(locale, copy.otherTickets, others.length)}
          </h4>
          <ul className="mt-2 flex list-none flex-col gap-1">
            {others.slice(0, 8).map((other: Ticket) => (
              <li key={other.id} className="text-xs text-white/48">
                {other.createdAt.slice(0, 10)} · {other.subject} · {copy.state[other.state]}
              </li>
            ))}
          </ul>
        </div>
      )}

      {error && (
        <InlineAlert variant="danger" title={copy.failedTitle} className="mt-4">
          {error}
        </InlineAlert>
      )}
    </div>
  );
}
