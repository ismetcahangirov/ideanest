import { authorizedFetch } from '../api/client';
import { errorFrom } from '../api/problem';

/**
 * §4.1's A-10 and A-11 — closing an account, and taking a copy of it first.
 *
 * <h2>Why they are one module</h2>
 *
 * They are one screen's worth of decisions and they are the two halves of the same moment:
 * §17.4 gives the deletion a thirty-day delay and then anonymisation, and the export is the
 * only way to keep anything after it. A screen that offered the deletion without the export
 * beside it would be a screen that quietly loses somebody's data.
 *
 * <h2>The deletion takes the password, and that is not friction</h2>
 *
 * §17.4: "a deletion that needed only a bearer credential would be a vandalism tool". An
 * access token is fifteen minutes of trust that a cross-site scripting bug, a shared machine
 * or a proxy log can leak. Cancelling takes nothing, deliberately and asymmetrically — the
 * safe direction should not be obstructed for the victim of a deletion they did not ask for.
 */

/**
 * What the service answers with when a deletion is accepted.
 *
 * `scheduledFor` is returned because, in `AccountDeletionController`'s words, "a confirmation
 * the user cannot check is not a confirmation". The screen shows the date rather than the
 * phrase "in thirty days", which is a promise about arithmetic nobody can verify.
 */
export interface DeletionSchedule {
  /** ISO-8601 instant, UTC. */
  readonly requestedAt: string;
  readonly scheduledFor: string;
}

export type DeletionOutcome =
  | { readonly kind: 'scheduled'; readonly schedule: DeletionSchedule }
  /** A genuine token for an account that is no longer there — the 404 `GET /v1/me` also gives. */
  | { readonly kind: 'already-gone' };

/**
 * Schedules the closure — `POST /v1/me/deletion`.
 *
 * 202 rather than 200, because what happened is that an instruction was accepted to be
 * carried out in thirty days. A wrong password is a refusal carrying the service's own
 * problem detail; §17.4's rate limit is per account rather than per address, because this
 * endpoint verifies a password and is therefore a password oracle for whoever holds a stolen
 * token — the screen surfaces the 429 rather than retrying into it.
 */
export async function requestDeletion(password: string): Promise<DeletionOutcome> {
  const response = await authorizedFetch('/v1/me/deletion', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password }),
  });

  if (response.status === 404) return { kind: 'already-gone' };
  if (!response.ok) throw await errorFrom(response);

  return { kind: 'scheduled', schedule: (await response.json()) as DeletionSchedule };
}

export type CancellationOutcome = 'cancelled' | 'nothing-pending';

/**
 * Withdraws a pending closure — `DELETE /v1/me/deletion`.
 *
 * A 404 means there was nothing scheduled, which from this screen is the state the reader
 * asked for rather than an error they can act on.
 */
export async function cancelDeletion(): Promise<CancellationOutcome> {
  const response = await authorizedFetch('/v1/me/deletion', { method: 'DELETE' });

  if (response.status === 404) return 'nothing-pending';
  if (!response.ok) throw await errorFrom(response);

  return 'cancelled';
}

/** The name the downloaded file gets, matching the service's own `Content-Disposition`. */
export const EXPORT_FILENAME = 'ideanest-account.json';

/**
 * Fetches the account export as a blob — `GET /v1/me/export`.
 *
 * **It cannot be a plain link, and that is a property of the token rather than a preference.**
 * The access token lives in a module variable and is sent as an `Authorization` header by
 * `authorizedFetch`; an `<a href="/v1/me/export">` is a navigation the browser makes on its
 * own, carrying no header and therefore no session. So the bytes are fetched here and handed
 * to the caller, which turns them into a download.
 *
 * §17.4 calls this "the single most valuable request an attacker with a stolen access token
 * can make" and rate limits it per account. A 429 surfaces as an `ApiError` and the screen
 * says to try later rather than retrying on the reader's behalf.
 */
export async function fetchAccountExport(): Promise<Blob> {
  const response = await authorizedFetch('/v1/me/export');
  if (!response.ok) throw await errorFrom(response);

  return response.blob();
}
