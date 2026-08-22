import { canonicalUrl, isPubliclyVisible, type EnvSource, type PublicProjectPreview } from '../metadata';
import { CATEGORIES_CRUMB, DISCOVER_CRUMB, HOME_CRUMB, breadcrumbNode, type Crumb } from './breadcrumb';
import type { JsonLdNode } from './document';
import { faqPageNode, type FaqEntry } from './faq';
import { siteIdentityNodes } from './identity';
import { rewardProductNodes, type PublicRewardTier } from './product';

/**
 * ONE GRAPH PER PAGE, COMPOSED HERE.
 *
 * The modules beside this one each know how to describe one kind of thing and
 * deliberately know nothing about where it is mounted. This is the only file
 * that decides which page claims what, so the answer to "what does a campaign
 * page say about itself" is one function rather than a component that a later
 * page forgets to copy — the same rule `lib/seo/metadata.ts` states for `<meta>`
 * tags, applied to the machine-readable half of the same job.
 */

/**
 * `/`, the home page — and therefore where the site's identity lives.
 *
 * **IT MOVED HERE FROM `/discover`, and that is the whole of the change #264 makes to this
 * file.** `identity.ts` explains the rule: Google reads site-level identity from the entry
 * page and asks for it on the home page or on one page describing the organisation, not on
 * every page. While there was no route at `/`, discovery was the entry page and the nodes
 * went there, with a comment saying so. There is one now.
 *
 * No breadcrumb. A trail on the page the trail starts from would be a single-item
 * `BreadcrumbList`, which `breadcrumbNode` refuses for the reason it gives — "you got here
 * from here" is not navigation.
 */
export function homePageGraph(env: EnvSource = process.env): readonly JsonLdNode[] {
  return [...siteIdentityNodes(env)];
}

/**
 * `/discover`.
 *
 * THE IDENTITY NODES ARE NO LONGER HERE. They are on `/`, which exists as of #264, and two
 * `Organization` nodes for one organisation across a crawl is exactly what `identity.ts`
 * argues against. What is left is the trail, which is what this page actually has to say
 * about itself.
 */
export function discoverPageGraph(env: EnvSource = process.env): readonly JsonLdNode[] {
  const breadcrumb = breadcrumbNode([HOME_CRUMB, DISCOVER_CRUMB], env);

  return breadcrumb === null ? [] : [breadcrumb];
}

/**
 * A category or subcategory landing page — §4.13 WS-05.
 *
 * A TRAIL AND NOTHING ELSE. There is no schema.org type that describes "a list of
 * crowdfunding campaigns filed under Games" without overclaiming: `CollectionPage` says the
 * page is *about* a collection, `ItemList` invites a rich result this platform has no
 * eligibility for, and both would be markup emitted because it exists rather than because a
 * consumer acts on it. The trail is the one true statement — these pages genuinely do sit
 * under `/categories`, and a subcategory genuinely does sit under its category.
 *
 * This is the first trail on the platform with a real intermediate step. The campaign page's
 * is `Home → Discover → campaign` and `breadcrumb.ts` says why it stops there: a crumb
 * naming a category had nowhere to point, because `/discover?category=…` is a URL robots.txt
 * disallows. These routes are what that comment was waiting for.
 */
export function categoryPageGraph(input: {
  readonly trail: readonly Crumb[];
  readonly env?: EnvSource;
}): readonly JsonLdNode[] {
  const breadcrumb = breadcrumbNode([HOME_CRUMB, CATEGORIES_CRUMB, ...input.trail], input.env ?? process.env);
  return breadcrumb === null ? [] : [breadcrumb];
}

export interface ProjectPageGraphInput {
  /** The public projection, or `null` when it could not be confirmed public. */
  readonly preview: PublicProjectPreview | null;
  /** The campaign page's route path — §10.2's `/projects/{creator}/{project}`. */
  readonly path: string;
  /** ISO 8601, or `null`. */
  readonly deadline: string | null;
  readonly tiers: readonly PublicRewardTier[];
  /** The pairs the page actually renders. See `faq.ts`. */
  readonly faqs: readonly FaqEntry[];
  readonly env?: EnvSource;
  /** Injected in tests; the wall clock otherwise. */
  readonly now?: Date;
}

/**
 * A campaign page's graph: the trail, its reward tiers, and its questions.
 *
 * **A campaign that could not be confirmed public produces an EMPTY graph** —
 * not a breadcrumb, not a title in a `ListItem`, nothing. That is the same lock
 * `projectPageMetadata` puts on the `<meta>` tags, in the same place in the same
 * order, and it is here for the same reason: the endpoint refuses a non-public
 * campaign, the reader refuses one that arrives anyway, and this refuses one
 * handed to it directly. A draft's title in a `BreadcrumbList` is a draft's title
 * in the HTML, whatever the `<title>` tag says.
 *
 * **The site identity is deliberately absent.** It is stated once, on the entry
 * page, and repeating it on every campaign would be two `Organization` nodes for
 * one organisation across the crawl.
 *
 * **MOUNTED SINCE #119**, on `app/projects/[id]/[projectSlug]/page.tsx`, which is
 * the server-rendered campaign page this was written ahead of. It is served the
 * same projection the page renders and the same tiers the page lists, so the
 * machine-readable and human-readable halves cannot describe two different
 * campaigns.
 *
 * `faqs` is still empty at that call site: `GET /v1/projects/{id}/faqs` is in
 * §10.2 and is not built. `faqPageNode` answers null for an empty list rather
 * than emitting an `FAQPage` with no questions in it, so the absence costs a
 * node and never a wrong one.
 */
export function projectPageGraph(input: ProjectPageGraphInput): readonly JsonLdNode[] {
  const { preview } = input;
  if (preview === null || !isPubliclyVisible(preview.state)) return [];

  const env = input.env ?? process.env;
  const campaignUrl = canonicalUrl(input.path, env);

  const breadcrumb = breadcrumbNode(
    [HOME_CRUMB, DISCOVER_CRUMB, { name: preview.title, path: input.path }],
    env,
  );
  const faq = faqPageNode(input.faqs, campaignUrl);

  return [
    ...(breadcrumb === null ? [] : [breadcrumb]),
    ...rewardProductNodes({
      campaignUrl,
      campaignState: preview.state,
      deadline: input.deadline,
      tiers: input.tiers,
      ...(input.now === undefined ? {} : { now: input.now }),
    }),
    ...(faq === null ? [] : [faq]),
  ];
}
