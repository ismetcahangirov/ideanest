import type { MetadataRoute } from 'next';

import { siteUrl } from '../lib/seo/sitemap/config';
import { discoveryEntries, pageEntries, projectEntries } from '../lib/seo/sitemap/entries';
import { indexableProjects } from '../lib/seo/sitemap/projects';
import { parseSitemapSegment, projectSlice, sitemapSegmentIds } from '../lib/seo/sitemap/segments';

/**
 * The segmented sitemap: `/sitemap/pages.xml`, `/sitemap/discovery.xml`,
 * `/sitemap/projects-0.xml`, and one more project shard per 45,000 campaigns.
 *
 * `generateSitemaps` is what makes `app/sitemap.ts` a dynamic segment; Next then
 * serves one file per id and `/sitemap.xml` is no longer a route. The index over
 * them is `app/sitemap_index.xml/route.ts`, which is where robots.txt points.
 *
 * `force-dynamic`, for two reasons that agree. The base URL is read from the
 * environment at request time, and a prerendered sitemap would carry whichever
 * origin the build machine had. And the campaign list comes from the service:
 * prerendering would make `next build` fail whenever the API is unreachable,
 * which is every CI run, and would freeze the campaign list at build time
 * besides. A sitemap is fetched rarely and is not on any reader's critical path;
 * `lib/seo/sitemap/projects.ts` memoises the walk for fifteen minutes so a crawl
 * of several segments still reads the feed once.
 */
export const dynamic = 'force-dynamic';

/**
 * The segments that exist.
 *
 * A FAILED READ FALLS BACK TO ONE PROJECT SHARD RATHER THAN THROWING. Next calls
 * this on every request to decide whether the requested id exists at all, and an
 * id missing from this list is a 404 — including `pages` and `discovery`, which
 * need no data whatsoever. A blink from the service must not take those two
 * down, and `projects-0` must keep its URL: a segment that disappears from the
 * index and comes back is a segment Search Console reports as removed. The shard
 * itself then answers honestly — with the data, or with a 500.
 */
export async function generateSitemaps(): Promise<Array<{ id: string }>> {
  let count = 0;

  try {
    count = (await indexableProjects()).length;
  } catch {
    count = 0;
  }

  return sitemapSegmentIds(count).map((id) => ({ id }));
}

/**
 * One segment.
 *
 * `id` arrives as a promise in Next 16 — the loader reads it off `ctx.params` —
 * and without the `.xml` suffix. An id Next did not generate is answered 404
 * before this runs; the empty array is belt and braces.
 */
export default async function sitemap({
  id,
}: {
  id: Promise<string | undefined>;
}): Promise<MetadataRoute.Sitemap> {
  const segment = parseSitemapSegment(await id);
  if (segment === null) return [];

  const baseUrl = siteUrl();

  if (segment.kind === 'pages') return pageEntries(baseUrl);
  if (segment.kind === 'discovery') return discoveryEntries(baseUrl);

  const projects = await indexableProjects();
  return projectEntries(projectSlice(projects, segment.index), baseUrl, new Date());
}
