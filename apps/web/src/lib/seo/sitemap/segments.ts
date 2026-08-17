/**
 * Segmentation and sharding.
 *
 * ONE FILE PER CONTENT TYPE, AND THEN ONE PER 45,000 URLS. A sitemap may hold
 * 50,000 URLs and 50 MB uncompressed, and a file that breaches either limit is
 * rejected whole rather than truncated — so one file for the whole platform is a
 * file that stops working at a size nobody is watching for.
 *
 * Segmenting by content type first is not only about size. The three kinds
 * change at completely different rates, and a crawler is told so: a segment
 * whose `Last-Modified` has not moved is a segment it can skip. Mixing a
 * campaign that changes hourly with a static page that changes yearly means
 * neither statement can be made.
 */

/**
 * URLs per project segment.
 *
 * 45,000 rather than 50,000, because the limit is a cliff: a 50,001-URL file is
 * not a file with one URL too many, it is a file that is not read. The 5,000
 * spare is also the 50 MB limit's headroom — a `<url>` here is roughly 150 bytes
 * of `<loc>`, `<lastmod>` and `<changefreq>`, so a full segment is about 7 MB.
 */
export const MAX_URLS_PER_SITEMAP = 45_000;

/** Static pages: the ones that are not a campaign and not a feed. */
export const PAGES_SEGMENT_ID = 'pages';

/** Discovery: the feed, and the category landing pages when they exist. */
export const DISCOVERY_SEGMENT_ID = 'discovery';

/** Project shards are `projects-0`, `projects-1`, and so on. */
export const PROJECT_SEGMENT_PREFIX = 'projects-';

/** Which segment an id names. */
export type SitemapSegment =
  | { readonly kind: 'pages' }
  | { readonly kind: 'discovery' }
  | { readonly kind: 'projects'; readonly index: number };

export function projectSegmentId(index: number): string {
  return `${PROJECT_SEGMENT_PREFIX}${index}`;
}

/**
 * How many project shards there are.
 *
 * AT LEAST ONE, always. A platform with no indexable campaigns still publishes
 * `projects-0`, as an empty `<urlset>`: an index that references a segment which
 * 404s is an error in Search Console, and a segment that appears and disappears
 * with the campaign count is a segment whose URL nobody can rely on.
 */
export function projectSegmentCount(indexableProjectCount: number): number {
  return Math.max(1, Math.ceil(indexableProjectCount / MAX_URLS_PER_SITEMAP));
}

/** Every segment id, in the order the index lists them. */
export function sitemapSegmentIds(indexableProjectCount: number): readonly string[] {
  const projects = Array.from({ length: projectSegmentCount(indexableProjectCount) }, (_, index) =>
    projectSegmentId(index),
  );

  return [PAGES_SEGMENT_ID, DISCOVERY_SEGMENT_ID, ...projects];
}

/**
 * The segment an id names, or null.
 *
 * STRICT ON PURPOSE. The id comes off the URL, so `projects-01`, `projects-1e3`
 * and `projects--1` are all things somebody can type. Each would resolve to
 * shard 1, 1000 and -1 under `Number()`, and the first two would then serve a
 * duplicate of a segment that already has a canonical URL. Only the exact
 * spelling `sitemapSegmentIds` produces is accepted.
 */
export function parseSitemapSegment(id: string | undefined): SitemapSegment | null {
  if (id === undefined) return null;
  if (id === PAGES_SEGMENT_ID) return { kind: 'pages' };
  if (id === DISCOVERY_SEGMENT_ID) return { kind: 'discovery' };

  if (!id.startsWith(PROJECT_SEGMENT_PREFIX)) return null;
  const suffix = id.slice(PROJECT_SEGMENT_PREFIX.length);
  if (!/^(0|[1-9][0-9]*)$/.test(suffix)) return null;

  return { kind: 'projects', index: Number(suffix) };
}

/** The slice of the whole set that belongs to one project shard. */
export function projectSlice<T>(items: readonly T[], index: number): readonly T[] {
  const start = index * MAX_URLS_PER_SITEMAP;
  return items.slice(start, start + MAX_URLS_PER_SITEMAP);
}
