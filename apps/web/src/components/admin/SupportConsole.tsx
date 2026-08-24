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
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the support queue';

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
export function SupportConsole() {
  const [page, setPage] = useState(0);
  const [openTicket, setOpenTicket] = useState<string | null>(null);

  const queue = useConsoleResource((signal) => readTicketQueue(page, signal), SUBJECT, [page]);

  if (queue.status === 'signed-out' || queue.status === 'forbidden') {
    return <ConsoleRefusal status={queue.status} subject={SUBJECT} />;
  }

  return (
    <div className="flex flex-col gap-8">
      <section aria-labelledby="queue-heading">
        <h2 id="queue-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Open tickets
          {queue.status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">
              {queue.data?.tickets.length ?? 0}
            </span>
          )}
        </h2>
        <p className="mt-2 max-w-[62ch] text-sm text-white/64">
          Most urgent first, oldest first within a priority. Priority is set by staff — one the
          person asking could choose would be urgent on every ticket within a week.
        </p>

        {queue.status === 'loading' && (
          <SkeletonGroup label="Loading the support queue" className="mt-4">
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
            <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
              {queue.error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={queue.reload}>
              Try again
            </Pill>
          </>
        )}

        {queue.status === 'ready' && queue.data !== null && queue.data.tickets.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title="Nothing is waiting"
            description="No ticket is open or pending. Tickets are recorded here by staff when somebody writes in — there is no public form behind this screen yet."
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
                      <Tag>{ticket.priority}</Tag>
                      <Tag>{ticket.state}</Tag>
                    </span>
                  </div>
                  <p className="mt-2 text-xs text-white/40">
                    <span className="font-mono">{shortId(ticket.requesterId)}</span> ·{' '}
                    {ticket.createdAt.slice(0, 10)}
                    {ticket.assigneeId == null
                      ? ' · unassigned'
                      : ` · ${shortId(ticket.assigneeId)}`}
                  </p>
                </button>

                {openTicket === ticket.id && <TicketDetail ticketId={ticket.id} onChanged={queue.reload} />}
              </li>
            ))}
          </ul>
        )}

        {queue.status === 'ready' && (page > 0 || (queue.data?.hasMore ?? false)) && (
          <div className="mt-4 flex gap-2">
            <Pill variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((n) => n - 1)}>
              Previous
            </Pill>
            <Pill
              variant="ghost"
              size="sm"
              disabled={!(queue.data?.hasMore ?? false)}
              onClick={() => setPage((n) => n + 1)}
            >
              Next
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
  onChanged,
}: {
  readonly ticketId: string;
  readonly onChanged: () => void;
}) {
  const context = useConsoleResource(
    (signal) => readTicket(ticketId, signal),
    'this ticket',
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
      setError(consoleMessageFor(cause, 'this ticket'));
    } finally {
      setBusy(false);
    }
  }

  if (context.status === 'loading') {
    return (
      <SkeletonGroup label="Loading the ticket" className="mt-2 rounded-lg border border-white/8 p-4">
        <Skeleton height="0.875rem" width="60%" />
      </SkeletonGroup>
    );
  }

  if (context.status !== 'ready' || context.data === null) {
    return (
      <InlineAlert variant="danger" title="The ticket could not be read" className="mt-2">
        {context.error ?? 'Try again.'}
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
              {message.authorSide === 'STAFF' ? 'Staff' : 'Requester'}
              {message.internal ? ' · internal note, never shown to them' : ''} ·{' '}
              {message.createdAt.slice(0, 10)}
            </p>
            <p className="mt-1 whitespace-pre-wrap text-sm text-white/80">{message.body}</p>
          </li>
        ))}
      </ul>

      <div className="mt-4 flex flex-col gap-2 border-t border-white/8 pt-4">
        <Field label="Reply">
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
            label="Internal note — the requester never sees this, and it does not move the ticket"
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
            Send
          </Pill>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-end gap-3 border-t border-white/8 pt-4">
        <Field label="Priority" className="min-w-[150px]">
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
                {option}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="State" className="min-w-[150px]">
          <Select
            value={file.ticket.state}
            disabled={busy}
            onChange={(event) =>
              void act(() => updateTicket(ticketId, { state: event.target.value as TicketState }))
            }
          >
            {TICKET_STATES.map((option) => (
              <option key={option} value={option}>
                {option}
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
          Put back in the queue
        </Pill>
      </div>

      {others.length > 0 && (
        <div className="mt-4 border-t border-white/8 pt-4">
          <h4 className="text-xs font-medium text-white/64">
            This account has asked us {others.length} other {others.length === 1 ? 'time' : 'times'}
          </h4>
          <ul className="mt-2 flex list-none flex-col gap-1">
            {others.slice(0, 8).map((other: Ticket) => (
              <li key={other.id} className="text-xs text-white/48">
                {other.createdAt.slice(0, 10)} · {other.subject} · {other.state}
              </li>
            ))}
          </ul>
        </div>
      )}

      {error && (
        <InlineAlert variant="danger" title="That did not work" className="mt-4">
          {error}
        </InlineAlert>
      )}
    </div>
  );
}
