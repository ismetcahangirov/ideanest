/**
 * The sitemap index.
 *
 * Next serves one file per segment at `/sitemap/{id}.xml` and writes no index of
 * them: `generateSitemaps` maps `app/sitemap.ts` onto a dynamic segment, and
 * `/sitemap.xml` is not a route at all once it does. So the index is ours to
 * write, and it is what `robots.txt` points a crawler at — one URL to submit,
 * whatever the shard count happens to be that week.
 */

/** Where the index is served. */
export const SITEMAP_INDEX_PATH = '/sitemap_index.xml';

/** Where Next serves one segment. */
export function segmentUrl(id: string, baseUrl: string): string {
  return `${baseUrl}/sitemap/${id}.xml`;
}

/**
 * The five characters XML cannot carry raw.
 *
 * A segment id is generated here and cannot contain any of them today. It is
 * escaped anyway: this function's output goes into a file byte for byte, and
 * "the input is safe" is a property of the caller, which is not where a rule
 * about XML belongs.
 */
function escaped(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

/**
 * The index over a set of segments.
 *
 * NO `<lastmod>`. On a `<sitemap>` element it means "when this sitemap file last
 * changed", and the only value available at generation time is the generation
 * time itself — which would say every segment changed on every request. The
 * field is optional, and omitting it leaves the crawler to compare the segments
 * themselves rather than to distrust a date it can see is wrong.
 */
export function sitemapIndexXml(segmentIds: readonly string[], baseUrl: string): string {
  const entries = segmentIds
    .map((id) => `  <sitemap>\n    <loc>${escaped(segmentUrl(id, baseUrl))}</loc>\n  </sitemap>`)
    .join('\n');

  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    entries,
    '</sitemapindex>',
    '',
  ].join('\n');
}
