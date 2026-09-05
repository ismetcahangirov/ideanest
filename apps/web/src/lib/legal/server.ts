import type { EnvSource } from '../seo/metadata';
import { apiOrigin } from '../seo/metadata-source';
import { LEGAL } from '../cache/tags';
import {
  kindOf,
  readLegalCatalogue,
  readLegalDocument,
  type LegalDocument,
  type LegalDocumentSlug,
  type LegalDocumentSummary,
} from './api';

/**
 * The legal pages' server reads — §22.2, issue #439.
 *
 * <h2>These routes stay static, and every choice here is in service of that</h2>
 *
 * #439 is explicit: "Static, and they must stay static: these are the pages a stranger and a
 * regulator read, they change a few times a year, and rendering them per request would be a
 * dynamic route for no reason. No client provider, no cookie read."
 *
 * So: `credentials: 'omit'`, no `Authorization` header, no `cookies()`, no `headers()`, and the
 * locale comes from `params` rather than from a request. Nothing about a read varies by reader,
 * which is what the requirement is actually about — a page that read a session would be one no
 * shared cache could hold. (Every route in this application builds as `ƒ` because of the locale
 * proxy; `legal/[document]/page.tsx` records why that is not the same question.)
 *
 * <h2>An hour, matching the service's own cache directive</h2>
 *
 * `LegalDocumentController` sends `Cache-Control: public, max-age=3600` for what is in force and
 * thirty days for an archived version. This window matches the first; the archive gets the same
 * one rather than thirty days, because a page revalidating hourly against an immutable document
 * costs one request an hour and a page pinned for a month is one a redeploy cannot correct.
 *
 * The {@link LEGAL} tag is what makes the window survivable in the direction that matters: a
 * version published this morning is what somebody accepting this afternoon is shown, because
 * publishing invalidates the tag rather than waiting out the hour.
 *
 * <h2>`null` is "not published", and the page renders that rather than failing</h2>
 *
 * The service answers 404 for a document with no published version, which is the state this
 * repository is in until #423's adviser delivers the words. That is not an error to apologise
 * for: §22.2 says the platform must *have* eight documents, and a page that said "something
 * went wrong" would be hiding the fact that one has not been written. `notFound()` is wrong
 * for the same reason — the address is real and permanent, and a 404 tells a crawler to stop
 * asking.
 */

export interface LegalReadOptions {
  /** Injected in tests. Defaults to the platform `fetch`. */
  readonly fetchImpl?: typeof fetch;
  readonly env?: EnvSource;
  readonly signal?: AbortSignal;
}

const LEGAL_REVALIDATE_SECONDS = 3600;

async function read(path: string, options: LegalReadOptions): Promise<unknown | null> {
  const fetchImpl = options.fetchImpl ?? fetch;

  try {
    const response = await fetchImpl(`${apiOrigin(options.env)}${path}`, {
      credentials: 'omit',
      headers: { accept: 'application/json' },
      next: { revalidate: LEGAL_REVALIDATE_SECONDS, tags: [LEGAL] },
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });

    if (!response.ok) return null;
    return (await response.json()) as unknown;
  } catch {
    return null;
  }
}

/**
 * The version of a document in force, in the reader's language.
 *
 * The service falls back to the governing Azerbaijani text when a language has not been
 * translated, so the `locale` on the answer is not necessarily the one asked for — and the page
 * says which language it got. A reader shown the governing text without being told is a reader
 * who thinks their language was chosen for them.
 */
export async function fetchLegalDocument(
  slug: LegalDocumentSlug,
  locale: string,
  options: LegalReadOptions = {},
): Promise<LegalDocument | null> {
  const body = await read(
    `/v1/legal/documents/${kindOf(slug)}?locale=${encodeURIComponent(locale)}`,
    options,
  );
  if (body === null) return null;

  return readLegalDocument(body);
}

/**
 * One archived version, by number.
 *
 * <strong>The reason the archive exists at all.</strong> Somebody who accepted version 3 must be
 * able to read version 3, not only whatever is current — otherwise the acceptance record names a
 * text the person it is about cannot see. V65 stores every version precisely so this read can
 * exist, and the trigger that makes a published version immutable is what makes it worth having.
 */
export async function fetchArchivedLegalDocument(
  slug: LegalDocumentSlug,
  version: number,
  locale: string,
  options: LegalReadOptions = {},
): Promise<LegalDocument | null> {
  const body = await read(
    `/v1/legal/documents/${kindOf(slug)}/versions/${version}?locale=${encodeURIComponent(locale)}`,
    options,
  );
  if (body === null) return null;

  return readLegalDocument(body);
}

/**
 * Everything published and in force, without the bodies.
 *
 * What the index draws, and what makes an absence visible: a platform that has not published its
 * creator agreement is a platform whose list is short. `null` is a refused read and an empty
 * list is a platform with nothing published, and the index says different things about them.
 */
export async function fetchLegalCatalogue(
  locale: string,
  options: LegalReadOptions = {},
): Promise<readonly LegalDocumentSummary[] | null> {
  const body = await read(`/v1/legal/documents?locale=${encodeURIComponent(locale)}`, options);
  if (body === null) return null;

  return readLegalCatalogue(body);
}
