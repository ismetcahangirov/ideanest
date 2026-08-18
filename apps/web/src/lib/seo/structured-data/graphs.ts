import { canonicalUrl, isPubliclyVisible, type EnvSource, type PublicProjectPreview } from '../metadata';
import { DISCOVER_CRUMB, HOME_CRUMB, breadcrumbNode } from './breadcrumb';
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
 * `/discover`, the entry page — and therefore where the site's identity lives.
 *
 * See `identity.ts` for why this is not in the root layout.
 */
export function discoverPageGraph(env: EnvSource = process.env): readonly JsonLdNode[] {
  const breadcrumb = breadcrumbNode([HOME_CRUMB, DISCOVER_CRUMB], env);

  return [...siteIdentityNodes(env), ...(breadcrumb === null ? [] : [breadcrumb])];
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
 * NOTHING MOUNTS THIS YET. The server-rendered campaign page is #119 and does
 * not exist — `app/projects/[id]/prelaunch/page.tsx` is the only campaign route
 * in this build, it is `PUBLIC_NOT_INDEXABLE` by design, and it has neither
 * reward tiers nor an FAQ on it. Composing the graph now means the page ships
 * with its structured data rather than acquiring it in a later pull request that
 * nobody files.
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
