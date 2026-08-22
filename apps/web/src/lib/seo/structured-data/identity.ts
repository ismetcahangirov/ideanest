import {
  SITE_DESCRIPTION,
  SITE_LANGUAGE,
  SITE_NAME,
  canonicalUrl,
  type EnvSource,
} from '../metadata';
import { withoutAbsent, type JsonLdNode } from './document';

/**
 * WHO THIS SITE IS, said once.
 *
 * Two nodes, and they answer two different questions. `Organization` is the
 * publisher — the legal thing that runs the platform. `WebSite` is the thing at
 * the origin, which is what carries the language and the name a search engine
 * prints above a result. Collapsing them into one node is common and wrong: a
 * site name and a company name are not required to be the same string, and the
 * day IdeaNest's are not, one of the two claims would silently become false.
 *
 * <h2>Where this is mounted, and why it is not the root layout</h2>
 *
 * Google reads site-level identity from the entry page and explicitly does not
 * want it on every page ("We recommend placing this information on your home
 * page, or a single page that describes your organization"). That is `/`, which
 * #264 built. Until then these nodes lived on `/discover`, which was the front
 * door in the literal sense — the application answered its own origin with a 404
 * — and `graphs.ts` records the move.
 *
 * Mounting them in the root layout would repeat the same two nodes inside every
 * campaign page, every editor tab and every checkout, which is bytes on every
 * response in exchange for nothing.
 *
 * <h2>What is deliberately absent</h2>
 *
 * **No `logo`.** Google wants a crawlable raster at 112×112 or larger and this
 * repository has no logo file — `app/opengraph-image.tsx` draws a 1200×630
 * social card, which is a picture of a sentence and not a mark. Naming it as the
 * organisation's logo would put the wrong image beside the site's name in a
 * knowledge panel, and there is no way to be half right about it.
 *
 * **No `sameAs`.** It is the property that links an organisation to the
 * profiles that corroborate it, so a guessed handle is an assertion that
 * somebody else's account is ours. The platform has no confirmed profiles in
 * the specification; when it does, they belong here and nowhere else.
 *
 * **No `potentialAction` / `SearchAction`.** That markup existed to produce
 * Google's sitelinks search box, which was retired globally on 21 November 2024
 * and whose documentation has been withdrawn. Emitting it now would be markup
 * that no consumer acts on, describing `/discover?q=` — a URL robots.txt
 * disallows wholesale (`lib/seo/indexability.ts`). Advertising a query endpoint
 * we have asked crawlers not to visit is a contradiction, not an enhancement.
 */

/** The node identifiers, as fragments on the site's own URL. */
export const ORGANIZATION_FRAGMENT = '#organization';
export const WEBSITE_FRAGMENT = '#website';

/**
 * The origin, through the SAME helper canonicals and `og:url` are built from.
 *
 * `canonicalUrl('/')` rather than a second reading of the environment. There is
 * one variable, `IDEANEST_SITE_URL`, and `lib/seo/metadata.ts` has the whole
 * account of what happened the last time two modules read two names for it.
 * A base carrying a path survives here exactly as it survives in the sitemap.
 */
function siteHomeUrl(env: EnvSource): string {
  return canonicalUrl('/', env);
}

export function organizationNode(env: EnvSource = process.env): JsonLdNode {
  const url = siteHomeUrl(env);

  return withoutAbsent({
    '@type': 'Organization',
    '@id': `${url}${ORGANIZATION_FRAGMENT}`,
    name: SITE_NAME,
    url,
    description: SITE_DESCRIPTION,
  });
}

export function webSiteNode(env: EnvSource = process.env): JsonLdNode {
  const url = siteHomeUrl(env);

  return withoutAbsent({
    '@type': 'WebSite',
    '@id': `${url}${WEBSITE_FRAGMENT}`,
    name: SITE_NAME,
    url,
    description: SITE_DESCRIPTION,
    /*
     * The same constant `<html lang>` and `og:locale` are built from, so the
     * language this page announces to a screen reader, to an unfurler and to a
     * crawler cannot drift apart.
     */
    inLanguage: SITE_LANGUAGE,
    // By reference, not by value: one `Organization` on the site, pointed at.
    publisher: { '@id': `${url}${ORGANIZATION_FRAGMENT}` },
  });
}

/** Both identity nodes, in the order a reader of the document would want them. */
export function siteIdentityNodes(env: EnvSource = process.env): readonly JsonLdNode[] {
  return [organizationNode(env), webSiteNode(env)];
}
