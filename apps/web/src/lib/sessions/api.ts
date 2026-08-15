import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * One live device, as `GET /v1/auth/sessions` returns it.
 *
 * Three fields are optional because the service serialises with
 * `default-property-inclusion: non_null` — a null `deviceLabel` is absent from
 * the JSON rather than present and null. They are also null for real reasons:
 * `deviceLabel` is only set when the client sent one at sign-in, and all three
 * are stripped when an account is anonymised.
 */
export interface SessionSummary {
  id: string;
  /** Client-supplied at sign-in, so untrusted. Only ever rendered as text. */
  deviceLabel?: string;
  userAgent?: string;
  ipAddress?: string;
  /** ISO-8601 instant, UTC. */
  createdAt: string;
  /** Advances on refresh, not on every request — so "last active" is coarse. */
  lastSeenAt: string;
  expiresAt: string;
  /** Matched against the `sid` claim on the caller's own access token. */
  current: boolean;
}

/**
 * The account's live devices, newest activity first.
 *
 * Revoked and expired sessions never appear, so a revoked row simply stops
 * being returned; there is no status to filter on here.
 */
export async function listSessions(signal?: AbortSignal): Promise<SessionSummary[]> {
  const response = await authorizedFetch('/v1/auth/sessions', { signal });
  if (!response.ok) throw await errorFrom(response);

  return (await response.json()) as SessionSummary[];
}

export type RevokeOutcome = 'revoked' | 'already-gone';

/**
 * Ends one device's session.
 *
 * A 404 means "unknown identifier" or "not yours", deliberately
 * indistinguishable so that this endpoint cannot be used to ask whether a
 * session id is real. From this screen's point of view both readings have the
 * same consequence — the row is not there — so it is reported as success rather
 * than as an error the user cannot act on.
 */
export async function revokeSession(id: string): Promise<RevokeOutcome> {
  const response = await authorizedFetch(`/v1/auth/sessions/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });

  if (response.status === 404) return 'already-gone';
  if (!response.ok) throw await errorFrom(response);

  return 'revoked';
}
