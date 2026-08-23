/**
 * The one way this application posts to `/v1/auth`.
 *
 * <h2>Why a module of its own</h2>
 *
 * `lib/auth/api.ts` opened with the rule "ONE MODULE, ONE PLACE" and then owned a private
 * `post` helper, which was correct while it was the only file calling those endpoints. It is
 * not any more: the two-factor challenge (#272) and the provider sign-ins (#273) finish a
 * sign-in that `signIn` started, and they have to be sent exactly the same way — same-origin,
 * with the client header, and never stored. Copying six lines into each would be three places
 * where "credentials: same-origin" could be forgotten, and forgetting it is silent: the
 * request succeeds, the `SameSite=Strict` refresh cookie is simply never stored, and the
 * account is signed out again on the next page load.
 *
 * So the helper moved here and `lib/auth/api.ts` imports it. Nothing else changed.
 *
 * <h2>What it guarantees</h2>
 *
 * Relative paths only, which is what makes the request same-origin — `next.config.mjs`
 * proxies `/v1` to the service. `X-IdeaNest-Client` on every call, including the ones that
 * carry no cookie yet, for the reason `lib/auth/api.ts` gives. And `no-store`, because a
 * credential exchange carries no validator to win a `304` with, so nothing is given up by
 * refusing to store it and what would be stored is somebody's sign-in.
 */

const CLIENT_HEADER = 'X-IdeaNest-Client';
const CLIENT_HEADER_VALUE = 'ideanest-web';

export async function postToAuth(path: string, body: unknown): Promise<Response> {
  return fetch(path, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      [CLIENT_HEADER]: CLIENT_HEADER_VALUE,
    },
    body: JSON.stringify(body),
    credentials: 'same-origin',
    cache: 'no-store',
  });
}
