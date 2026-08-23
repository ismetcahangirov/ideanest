import { publicFetch } from '../api/client';
import { errorFrom } from '../api/problem';
import type { ProfileLocation } from './api';

/**
 * V16's closed vocabulary of places — `GET /v1/locations`, for §4.2's P-02 (issue #276).
 *
 * <h2>Eighteen rows, and the client holds none of them</h2>
 *
 * There is no `LOCATIONS` constant in this application and there must not be one. The
 * gazetteer is reference data in the service's own schema, the profile editor's `<select>` is
 * the only screen that offers it, and a copy here would be a second list to keep in step with
 * a table this repository does not own — the same argument `lib/categories/api.ts` makes about
 * the taxonomy, for the same reason. A place added to the table appears in the editor without
 * anybody shipping a deployment.
 *
 * <h2>It is the same vocabulary `?city=` reads, which is the point of it being one table</h2>
 *
 * `PostgresSearchService` filters discovery on these slugs and `ProfileLocations` resolves a
 * profile's own against them. A profile that says "Gəncə" and a filter that offers "Ganja"
 * would be two spellings of one place and a filter that silently found nobody.
 *
 * **The name is resolved per `Accept-Language` by the service**, exactly as `GET /v1/categories`
 * resolves a category's. Nothing here picks a language: a client that chose one would be
 * choosing on the reader's behalf and would have to be changed again when §21.1's routing
 * lands.
 *
 * <h2>`publicFetch`, because the list is not about anybody</h2>
 *
 * It is `permitAll` and carries no personal data — a gazetteer. `authorizedFetch` would throw
 * without a token, which is the wrong shape for a list that would be just as correct on a
 * signed-out screen, and it would forbid the conditional revalidation `publicFetch`'s
 * `no-cache` is there to allow: eighteen rows that change perhaps never are exactly what a
 * `304` is for.
 */

/** The wire shape. `items`, as every list this service sends is keyed. */
interface RawLocationPage {
  readonly items?: readonly RawLocation[];
}

/**
 * One row as the endpoint sends it.
 *
 * `name` is optional here and falls back to the slug **only** so that a malformed response
 * cannot put `undefined` into an `<option>`. `lib/projects/api.ts` does the identical thing
 * for a category and gives the identical reason; it is a guard against a broken response, not
 * a language fallback this client is entitled to make.
 */
interface RawLocation {
  readonly slug: string;
  readonly name?: string;
}

/**
 * Every place a profile may claim, in the order the service lists them.
 *
 * The order is the service's and is deliberately not re-sorted here. `Intl.Collator` on a
 * mixed-script list would order it by whichever locale this browser happens to be in, so two
 * readers would see two different lists of the same eighteen places and neither would match
 * the order the discovery filter shows.
 */
export async function listProfileLocations(
  signal?: AbortSignal,
): Promise<readonly ProfileLocation[]> {
  const response = await publicFetch('/v1/locations', { signal });
  if (!response.ok) throw await errorFrom(response);

  const body = (await response.json()) as RawLocationPage;

  return (body.items ?? []).map((row) => ({ slug: row.slug, name: row.name ?? row.slug }));
}
