import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.11's AD-10: support conversations with the account context they are about — issue #310.
 *
 * <h2>Why the platform has its own rather than a shared mailbox</h2>
 *
 * §4.11 asks for "tickets with user context and action history", and the second half is what
 * a mail client cannot do. A conversation about a pledge is read beside that pledge, the
 * account's standing, and every other ticket the same person has raised — so the subject has
 * to be a column rather than a sentence somebody pasted, and the history has to come back
 * with the ticket.
 *
 * <h2>Internal notes are in the thread and are never shown to the requester</h2>
 *
 * A note staff leave for each other is read in sequence with the rest of the conversation —
 * that is the point of it — so it is a flag on a message rather than a separate list. The
 * service filters; `readRequesterThread` is what a person uses to check what the other side
 * actually has in front of them before replying.
 */

export type TicketState = 'OPEN' | 'PENDING' | 'RESOLVED' | 'CLOSED';

/** Set by staff, never by the requester — a priority anybody could choose is URGENT within a week. */
export type TicketPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';

export type TicketSubjectType = 'NONE' | 'PROJECT' | 'PLEDGE' | 'ACCOUNT';

export type TicketSide = 'REQUESTER' | 'STAFF';

export interface Ticket {
  id: string;
  requesterId: string;
  subject: string;
  subjectType: TicketSubjectType;
  subjectRef?: string | null;
  state: TicketState;
  priority: TicketPriority;
  /** Null is the queue. There is deliberately no separate queue table. */
  assigneeId?: string | null;
  createdAt: string;
  updatedAt: string;
  resolvedAt?: string | null;
}

export interface TicketMessage {
  id: string;
  authorId: string;
  authorSide: TicketSide;
  body: string;
  /** Staff-only. The service refuses one attributed to the requester. */
  internal: boolean;
  createdAt: string;
}

export interface TicketFile {
  ticket: Ticket;
  messages: TicketMessage[];
}

/** A ticket, its thread, and everything else this person has asked. */
export interface TicketContext {
  file: TicketFile;
  /** Includes the ticket itself — a list of "their other tickets" that omitted one would be miscounted. */
  history: Ticket[];
}

export interface TicketPage {
  tickets: Ticket[];
  page: number;
  hasMore: boolean;
}

/** How urgent each level is, in order. The service sorts by the enum, so this order matters. */
export const TICKET_PRIORITIES: readonly TicketPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];

export const TICKET_STATES: readonly TicketState[] = ['OPEN', 'PENDING', 'RESOLVED', 'CLOSED'];

/** The queue: most urgent first, oldest first within a priority. */
export async function readTicketQueue(page = 0, signal?: AbortSignal): Promise<TicketPage> {
  const response = await authorizedFetch(`/v1/admin/tickets/queue?page=${page}`, { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TicketPage;
}

/** Everything, newest first, optionally narrowed to one state. */
export async function listTickets(
  state: TicketState | null,
  page = 0,
  signal?: AbortSignal,
): Promise<TicketPage> {
  const parameters = new URLSearchParams({ page: String(page) });
  if (state != null) parameters.set('state', state);

  const response = await authorizedFetch(`/v1/admin/tickets?${parameters}`, { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TicketPage;
}

/** One ticket, its thread, and the rest of this person's history. */
export async function readTicket(ticketId: string, signal?: AbortSignal): Promise<TicketContext> {
  const response = await authorizedFetch(`/v1/admin/tickets/${encodeURIComponent(ticketId)}`, {
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TicketContext;
}

/** The thread as the requester sees it — no internal notes. */
export async function readRequesterThread(
  ticketId: string,
  signal?: AbortSignal,
): Promise<{ messages: TicketMessage[] }> {
  const response = await authorizedFetch(
    `/v1/admin/tickets/${encodeURIComponent(ticketId)}/thread`,
    { signal },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as { messages: TicketMessage[] };
}

export interface RaiseTicketRequest {
  readonly requesterId: string;
  readonly subject: string;
  readonly subjectType: TicketSubjectType;
  readonly subjectRef: string | null;
  readonly priority: TicketPriority;
  readonly body: string;
  readonly signal?: AbortSignal;
}

/**
 * Records a conversation against somebody's account.
 *
 * Staff-only, and there is no public form behind it. That is a limit of #310 rather than an
 * oversight: a public "contact us" is a different surface with its own rate limiting and an
 * open question about senders who have no account.
 */
export async function raiseTicket(request: RaiseTicketRequest): Promise<TicketFile> {
  const response = await authorizedFetch('/v1/admin/tickets', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      requesterId: request.requesterId,
      subject: request.subject,
      subjectType: request.subjectType,
      subjectRef: request.subjectRef,
      priority: request.priority,
      body: request.body,
    }),
    signal: request.signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TicketFile;
}

/** Answers a ticket, or leaves a note. A note does not move it — they are still waiting. */
export async function replyToTicket(
  ticketId: string,
  body: string,
  internal: boolean,
  signal?: AbortSignal,
): Promise<TicketFile> {
  const response = await authorizedFetch(
    `/v1/admin/tickets/${encodeURIComponent(ticketId)}/messages`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body, internal }),
      signal,
    },
  );
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as TicketFile;
}

export interface TicketUpdate {
  readonly assigneeId?: string | null;
  /**
   * Puts it back in the queue.
   *
   * A flag rather than a null assignee, because JSON cannot tell "absent" from "null" — and
   * treating an omitted assignee as an unassignment would empty the queue every time
   * somebody changed a priority.
   */
  readonly unassign?: boolean;
  readonly priority?: TicketPriority;
  readonly state?: TicketState;
}

/** Changes the assignee, the priority, the state, or any combination. */
export async function updateTicket(
  ticketId: string,
  update: TicketUpdate,
  signal?: AbortSignal,
): Promise<Ticket> {
  const response = await authorizedFetch(`/v1/admin/tickets/${encodeURIComponent(ticketId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      assigneeId: update.assigneeId ?? null,
      unassign: update.unassign ?? false,
      priority: update.priority ?? null,
      state: update.state ?? null,
    }),
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as Ticket;
}
