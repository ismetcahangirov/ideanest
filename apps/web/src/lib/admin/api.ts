import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * ONE MODULE, ONE PLACE for everything the account directory asks the service.
 *
 * §4.11's AD-04 (#104). Four endpoints on one resource: the list, one account,
 * the ban, and the way back from it.
 *
 * Every one of them returns an email address, which almost nothing else on this
 * platform does — the service audits each read for that reason and answers
 * `no-store`. Nothing here caches a response, and nothing here holds one outside
 * the component that asked for it.
 *
 * `POST .../reinstate` is not in docs/architecture.md §10.2's list and exists
 * because a ban with no reversal makes the first mistaken one permanent. A
 * campaign's suspension is terminal because its funding window has moved on; an
 * account has no window.
 */

const JSON_HEADERS = { 'Content-Type': 'application/json' } as const;

/** What `users.suspension_reason` may hold — the `@Size` the request body carries. */
export const REASON_MAX_CHARACTERS = 2000;

/**
 * One account as staff see it.
 *
 * `suspendedBy` is an account id and not a name: there is no endpoint that turns
 * one into a person, so this screen shows the identifier and says that is what it
 * is — the same choice the moderation queue makes about `moderatorId`.
 */
export interface AdminUser {
  id: string;
  email: string;
  name: string;
  slug: string;
  emailVerified: boolean;
  /** ISO-8601 instant, UTC, or null when the address has never been proven. */
  emailVerifiedAt: string | null;
  suspended: boolean;
  suspendedAt: string | null;
  suspendedBy: string | null;
  suspensionReason: string | null;
  /** V5's grace period. Present because an account may be both suspended and leaving. */
  deletionScheduledAt: string | null;
  createdAt: string;
}

/**
 * A page, and where the next one starts.
 *
 * `nextCursor` is keyset rather than an offset: accounts are created underneath
 * the reader, and an offset would drift a moderator past the account they are
 * paging towards. Null means this page is the end.
 */
export interface AdminUserPage {
  users: AdminUser[];
  nextCursor: string | null;
}

export interface DirectoryRequest {
  /** Matched against address, display name and profile slug. Blank is unfiltered. */
  readonly query?: string;
  /** The "who is stopped" list. */
  readonly suspendedOnly?: boolean;
  /** The previous page's `nextCursor`. */
  readonly after?: string | null;
  readonly signal?: AbortSignal;
}

/** The service's own default; sent explicitly so the cursor and the page agree. */
export const DIRECTORY_PAGE_SIZE = 25;

function directoryQuery(request: DirectoryRequest): string {
  const parameters = new URLSearchParams();
  const term = request.query?.trim() ?? '';

  if (term !== '') parameters.set('query', term);
  if (request.suspendedOnly === true) parameters.set('suspended', 'true');
  if (request.after != null && request.after !== '') parameters.set('after', request.after);
  parameters.set('limit', String(DIRECTORY_PAGE_SIZE));

  return parameters.toString();
}

/** AD-04's search. Audited by the service, which is why nothing here retries it in a loop. */
export async function listUsers(request: DirectoryRequest = {}): Promise<AdminUserPage> {
  const response = await authorizedFetch(`/v1/admin/users?${directoryQuery(request)}`, {
    signal: request.signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as AdminUserPage;
}

/**
 * AD-04's ban.
 *
 * The reason is required by the service and by this signature: the person is told
 * it, and an appeal is answered from it.
 */
export async function banUser(id: string, reason: string, signal?: AbortSignal): Promise<AdminUser> {
  const response = await authorizedFetch(`/v1/admin/users/${encodeURIComponent(id)}/ban`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ reason }),
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as AdminUser;
}

/** The way back. Sessions are not restored — the person signs in again. */
export async function reinstateUser(id: string, signal?: AbortSignal): Promise<AdminUser> {
  const response = await authorizedFetch(`/v1/admin/users/${encodeURIComponent(id)}/reinstate`, {
    method: 'POST',
    signal,
  });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as AdminUser;
}
