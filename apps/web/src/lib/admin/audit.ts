import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.11's AD-14: the record of every privileged action — issue #314.
 *
 * <p>`audit_logs` has been written to since #107 and nothing has ever read it except a psql
 * session. What this module talks to is the read that arrived with this epic, over rows the
 * database refuses to change: V21 puts a trigger on the table that raises on UPDATE, DELETE
 * and TRUNCATE, so nothing here can be a stale copy of something that has since been edited.
 *
 * <p><strong>Two callers, and the second is the reason the filter matters.</strong>
 * `/admin/audit` is the trail. `/admin/moderation/{id}` asks the same endpoint for the
 * history of one report, which is what makes AD-01's decision detail a history rather than a
 * snapshot of the current state.
 */

/** What an actor was acting as — not the same question as who they were. */
export type AuditActorType = 'USER' | 'MODERATOR' | 'SYSTEM';

/**
 * Whether the action happened or was refused.
 *
 * A refusal is a row: the attempt is what happened, and a trail holding only the successes
 * cannot show somebody trying.
 */
export type AuditOutcome = 'SUCCEEDED' | 'REFUSED';

/**
 * One privileged action.
 *
 * Optional fields are `?: T | null` because the service serialises with
 * `default-property-inclusion: non_null` — an absent source address is absent from the JSON
 * rather than present and null.
 */
export interface AuditEntry {
  id: string;
  /** ISO-8601 instant, UTC, from the database's clock rather than the application's. */
  occurredAt: string;
  actorType: AuditActorType;
  /** Absent when the actor was the platform itself. */
  actorId?: string | null;
  /**
   * Whom the action was taken for, when somebody else took it.
   *
   * Absent on effectively every row today: the column exists for AD-04's audited
   * impersonation (#299), which is unbuilt and blocked on a policy question.
   */
  onBehalfOfId?: string | null;
  /** The service's own spelling — `project.approved`, `report.upheld`, `account.suspended`. */
  action: string;
  entityType: string;
  entityId: string;
  outcome: AuditOutcome;
  /** Redacted on the way in, per §17.4. Absent where the request had none. */
  sourceAddress?: string | null;
  userAgent?: string | null;
  /** The correlation identifier this row shares with §18.1's log lines for the same request. */
  requestId?: string | null;
  traceId?: string | null;
  /** What the action recorded about itself. Prose, and only ever rendered as text. */
  detail?: string | null;
}

export interface AuditTrailPage {
  /** Echoed, and absent when the request asked about every kind. See {@link TrailRequest}. */
  entityType?: string | null;
  entityId?: string | null;
  actorId?: string | null;
  entries: AuditEntry[];
  /** Absent on the last page. Keyset over the identifier, which is a UUID v7 (§7.3). */
  nextCursor?: string | null;
}

/**
 * Under the service's default page and well under its ceiling.
 *
 * Twenty-five, matching every other console list. A first paint holding a hundred audit rows
 * is a hundred rows nobody has read.
 */
export const TRAIL_PAGE_SIZE = 25;

/**
 * What the trail may be narrowed by, which is less than a reader might expect.
 *
 * <p>The service accepts an entity kind, one entity, or one actor, and nothing else — those
 * are the three indexes V21 created, and a filter outside them is a sequential scan over the
 * one table on the platform that only ever grows and is never pruned. Notably absent, and
 * each one migration away on the day somebody needs it: a filter on the action, a date range,
 * and any search over `detail`.
 *
 * <p>An entity identifier without a kind is <strong>dropped by the service</strong> rather
 * than applied — the index leads on the kind — and the response says so by echoing an empty
 * filter. This client sends what it is given and reads back what was used.
 */
export interface TrailRequest {
  entityType?: string | null;
  entityId?: string | null;
  actorId?: string | null;
  /** The previous page's `nextCursor`. */
  after?: string | null;
  limit?: number;
  signal?: AbortSignal;
}

export function trailQuery(request: TrailRequest): string {
  const params = new URLSearchParams();
  params.set('limit', String(request.limit ?? TRAIL_PAGE_SIZE));
  if (request.entityType != null && request.entityType !== '')
    params.set('entityType', request.entityType);
  if (request.entityId != null && request.entityId !== '') params.set('entityId', request.entityId);
  if (request.actorId != null && request.actorId !== '') params.set('actorId', request.actorId);
  if (request.after != null && request.after !== '') params.set('after', request.after);
  return params.toString();
}

/** One page of the trail, newest first. */
export async function readTrail(request: TrailRequest = {}): Promise<AuditTrailPage> {
  const response = await authorizedFetch(`/v1/admin/audit?${trailQuery(request)}`, {
    signal: request.signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as AuditTrailPage;
}

/**
 * The entity kinds the trail is worth narrowing to.
 *
 * <p>Taken from `AuditAction.entityType()` on the service side — the values are open text in
 * the column and a closed set on the writing side, so this list is what the platform can
 * actually produce rather than a guess. A kind that stopped being written would show as an
 * empty page rather than as a missing option, which is the honest failure of the two.
 *
 * <p><strong>Values only since #324.</strong> This used to be a list of pairs carrying the
 * English word for each kind, which put a translatable sentence in a module a server renders
 * and a client imports. The words are `admin.screens.audit.entity` now, keyed by these same
 * values, and this array stays the one place that says which kinds exist.
 */
export const AUDIT_ENTITY_TYPES: readonly string[] = Object.freeze([
  'project',
  'account',
  'report',
  'collaborator',
  'session',
  'collection',
]);

/**
 * The action, in words, from a table the caller resolved.
 *
 * <p>A lookup with a fallback rather than an exhaustive map, and the fallback is the point:
 * the service's set grows with every feature that adds a privileged action, and a screen that
 * rendered nothing for an action it had not been taught would be hiding exactly the row
 * somebody is looking for. An unknown action shows its wire spelling, which is readable.
 *
 * <p>The table is a parameter rather than a constant here because this module is imported by a
 * client component, and a catalogue cannot be read from one. The screen resolves it on the
 * server and hands it down, which is what every other translated component in this application
 * does.
 */
export function actionLabel(action: string, labels: Readonly<Record<string, string>>): string {
  return labels[action] ?? action;
}
