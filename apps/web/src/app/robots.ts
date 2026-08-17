import type { MetadataRoute } from 'next';

import { PRIVATE_PATH_PREFIXES } from '../lib/seo/indexability';
import { siteUrl } from '../lib/seo/sitemap/config';
import { SITEMAP_INDEX_PATH } from '../lib/seo/sitemap/index-xml';

/**
 * `/robots.txt`.
 *
 * ONE RULE FOR EVERY CRAWLER. Per-agent rules are how a robots.txt comes to say
 * different things to Google and to Bing by accident, and there is nothing here
 * that should depend on who is asking: the private surfaces are private because
 * of what is on them.
 *
 * `Allow: /` FIRST, THEN THE EXCEPTIONS. The public surface is the default and
 * the disallow list is the exception, rather than the reverse — a robots.txt
 * built the other way round quietly stops indexing every page added after it was
 * written. The list itself is `PRIVATE_PATH_PREFIXES`, beside the indexability
 * predicate, so that "must never be indexed" is stated once.
 *
 * `force-dynamic` because the base URL is read from the environment at request
 * time. A prerendered robots.txt would carry whichever origin was set on the
 * machine that ran `next build`, which is not necessarily the machine's it is
 * deployed to.
 */
export const dynamic = 'force-dynamic';

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [
      {
        userAgent: '*',
        allow: '/',
        disallow: [...PRIVATE_PATH_PREFIXES],
      },
    ],
    /*
     * The index, not the segments. The shard count changes with the number of
     * campaigns, and a robots.txt listing each segment would be a file that has
     * to be redeployed when the platform grows.
     */
    sitemap: `${siteUrl()}${SITEMAP_INDEX_PATH}`,
  };
}
