import { generateSitemaps } from '../sitemap';
import { siteUrl } from '../../lib/seo/sitemap/config';
import { sitemapIndexXml } from '../../lib/seo/sitemap/index-xml';

/**
 * `/sitemap_index.xml` — the index over the segments `app/sitemap.ts` produces.
 *
 * A hand-written route because Next does not write one. `generateSitemaps` maps
 * `app/sitemap.ts` onto `/sitemap/[id].xml` and takes `/sitemap.xml` with it, and
 * a `<sitemapindex>` is a different document from the `<urlset>` Next's sitemap
 * resolver emits — there is no shape of `MetadataRoute.Sitemap` that produces
 * one. The segment list is read from `generateSitemaps` itself rather than
 * recomputed, so the index cannot reference a segment that would 404.
 *
 * The name is `sitemap_index.xml` and not `sitemap.xml`: Next claims anything
 * matching `/sitemap.(xml|ts|tsx|js|jsx)` as a metadata route, so a route handler
 * at `app/sitemap.xml/route.ts` would collide with `app/sitemap.ts`.
 */
export const dynamic = 'force-dynamic';

export async function GET(): Promise<Response> {
  const segments = await generateSitemaps();
  const xml = sitemapIndexXml(
    segments.map((segment) => segment.id),
    siteUrl(),
  );

  return new Response(xml, {
    headers: {
      'content-type': 'application/xml; charset=utf-8',
      /*
       * The same policy Next puts on the segments themselves
       * (`next-metadata-route-loader`): revalidate on every request rather than
       * refusing to store. A crawler that asks twice in a minute may have the
       * cached copy; one that asks tomorrow gets a fresh list of shards.
       */
      'cache-control': 'public, max-age=0, must-revalidate',
    },
  });
}
