import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { CampaignOutcomeNotice } from '../../../../components/project/CampaignOutcomeNotice';
import { CampaignRewards } from '../../../../components/project/CampaignRewards';
import { CampaignStory } from '../../../../components/project/CampaignStory';
import { CampaignSummary } from '../../../../components/project/CampaignSummary';
import { StructuredData } from '../../../../components/seo/StructuredData';
import { fetchCampaignPage, fetchPublicRewards } from '../../../../lib/api/server';
import { previewOf, readCampaignPage, tiersOf } from '../../../../lib/projects/publicPage';
import { projectPageRobots } from '../../../../lib/seo/indexability';
import { projectPageMetadata } from '../../../../lib/seo/metadata';
import { REALTIME_ORIGIN_VARIABLE } from '../../../../lib/realtime/updates';
import { projectPageGraph } from '../../../../lib/seo/structured-data/graphs';

/**
 * The public campaign page — §4.4, at §10.2's `/projects/{creatorSlug}/{projectSlug}`.
 *
 * <h2>#119, and what "server-rendered" has to mean here</h2>
 *
 * The requirement is that the content is in the initial HTML rather than assembled by the
 * client. This page fetches the campaign and its reward tiers on the server and renders
 * them; there is no `'use client'` anywhere beneath it and no loading state, because there
 * is nothing to load. A crawler, a link unfurler and a reader on a slow connection are all
 * served the same complete document.
 *
 * That is also why the endpoint behind it carries the story: a page whose body arrived in a
 * second request would satisfy the letter of "server-rendered" and fail the thing it is for.
 *
 * <h2>Why the folder is `[id]/[projectSlug]` when the first segment is a creator's slug</h2>
 *
 * Next allows exactly one slug name per dynamic level, and `app/projects/[id]/` already
 * exists — it carries `/edit`, `/back` and `/prelaunch`, where the segment really is a
 * campaign identifier. A sibling `[creatorSlug]` is a build error, so the public page reuses
 * the name and binds it to `creatorSlug` below. **The URL is correct and the folder name is
 * a framework artefact**; renaming it belongs with whatever moves the creator's own routes
 * out from under `/projects`.
 *
 * There is no ambiguity at request time. `/{id}/edit` and its siblings are literal-segment
 * patterns, which Spring's counterpart and Next's router both prefer over two variables, so
 * `/projects/{uuid}/edit` never reaches this page.
 *
 * <h2>404, and the three things it covers</h2>
 *
 * A URL that resolves to nothing, a campaign whose state has no public page, and a service
 * that refused the read are one answer. `PublicProjects` argues it on the server side and
 * `readCampaignPage` re-checks the state here — the second lock on the same door.
 * `notFound()` renders the 404 route with a 404 status, which is what a crawler needs in
 * order to stop asking.
 */

/** The route's own path, for the canonical URL and the structured data's trail. */
function pathOf(creatorSlug: string, projectSlug: string): string {
  return `/projects/${encodeURIComponent(creatorSlug)}/${encodeURIComponent(projectSlug)}`;
}

/**
 * The title, description, canonical and social card of one campaign.
 *
 * **A second fetch is not made here.** Next deduplicates `fetch` within a render, and both
 * calls go through the same `next: { revalidate }` cache entry, so `generateMetadata` and
 * the component below read one response. That is the arrangement that makes it safe for the
 * `<head>` and the `<body>` to describe the campaign from the same projection rather than
 * from two reads a second apart.
 *
 * A campaign that cannot be confirmed public gets `noindex`, no canonical, and nothing said
 * about it — `projectPageMetadata` explains why the failure mode has to be a card that says
 * nothing rather than one that says something private.
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string; projectSlug: string }>;
}): Promise<Metadata> {
  const { id: creatorSlug, projectSlug } = await params;
  const campaign = readCampaignPage(await fetchCampaignPage(creatorSlug, projectSlug), creatorSlug);

  const metadata = projectPageMetadata(
    campaign === null ? null : previewOf(campaign),
    pathOf(creatorSlug, projectSlug),
  );
  if (campaign === null) return metadata;

  /*
   * #122's decision, applied to the page it was written for. `projectPageRobots` says of
   * itself that nothing rendered it yet because this page did not exist; it does now.
   *
   * Two of the nine states this page serves are public and deliberately not indexed, and
   * they are not the same two `projectPageMetadata` withholds a card from. `CANCELED` is a
   * campaign the creator withdrew: its backers keep the link and a permanent search result
   * for it would outrank the live campaigns beside it. `PRELAUNCH` is an unmoderated teaser
   * at a URL that stops existing the moment the campaign opens.
   *
   * `follow` stays true for both. Such a page still links to its creator, its category and
   * the feed, and every one of those is indexable — `nofollow` would throw away the one
   * useful thing an unindexed page does. The sitemap filters on the same predicate, so a
   * page and its entry cannot disagree.
   */
  return { ...metadata, robots: projectPageRobots(campaign.state) };
}

export default async function CampaignPage({
  params,
}: {
  params: Promise<{ id: string; projectSlug: string }>;
}) {
  const { id: creatorSlug, projectSlug } = await params;

  const campaign = readCampaignPage(await fetchCampaignPage(creatorSlug, projectSlug), creatorSlug);
  if (campaign === null) notFound();

  /*
   * The tiers are fetched only once the campaign is known to be public. Asking for them
   * first would put a request on the service for every crawl of a URL that does not exist —
   * and would do it with an identifier this page does not have until the first read answers.
   */
  const tiers = tiersOf(await fetchPublicRewards(campaign.id));

  return (
    <main className="mx-auto w-full max-w-[1200px] px-5 py-10 sm:px-6">
      {/*
        The structured data was written before this page existed — `projectPageGraph` says
        so — and mounting it is the last step of #121. It is emitted outside every other
        element so that it is in the first bytes a crawler reads rather than after a
        photograph.

        `faqs` is empty: `GET /v1/projects/{id}/faqs` is in §10.2 and is not built, and
        `faqPageNode` returns null for an empty list rather than an empty `FAQPage` — a
        structured-data node claiming a page has questions when it has none is worse than
        no node.
      */}
      <StructuredData
        nodes={projectPageGraph({
          preview: previewOf(campaign),
          path: pathOf(creatorSlug, projectSlug),
          deadline: campaign.deadline,
          tiers,
          faqs: [],
        })}
      />

      {/*
        NO ENTRY ANIMATION, AND THAT IS A DECISION RATHER THAN AN OVERSIGHT.

        docs/motion-system.md §5 gives the project page a "moderate" budget — section
        headings, counters, a sticky call to action — so a `FadeUp` on this header would be
        within it. It is not here because of what it costs on this particular page.

        `FadeUp` reaches `motion/react`, which `@ideanest/ui/motion` measures at 116 kB
        uncompressed and which is why that entry point exists at all. Reaching it from a
        Server Component also requires a client wrapper, so the whole header would become a
        client boundary. Paying both on the page whose largest contentful paint is the
        entire subject of #119 — to fade one heading in once — is the trade that issue
        exists to refuse.

        It becomes worth revisiting when the page has something else on it that animates,
        which §5's own list says it will: a counter on the funding figure and the sticky
        call to action that arrives with the pledge flow. At that point the runtime is being
        paid for anyway.
      */}
      {/*
        §12.1's live counter is opt-in, and read here so the page decides once. Unset — the
        default — means no socket is opened and this page behaves exactly as it did before
        #91. `lib/realtime/updates.ts` explains why it cannot simply use the `/v1` rewrite
        every other browser call goes through.
      */}
      <CampaignSummary campaign={campaign} realtimeOrigin={process.env[REALTIME_ORIGIN_VARIABLE]} />

      <div className="mt-12 grid gap-10 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)] lg:items-start">
        <div className="flex flex-col gap-8">
          {campaign.outcome !== null && <CampaignOutcomeNotice campaign={campaign} />}

          {campaign.story !== null && <CampaignStory story={campaign.story} title={campaign.title} />}

          {campaign.risks !== null && (
            /*
              §5.5 makes the risks section a creator obligation and §5.3 requires two hundred
              characters of it before a campaign may be submitted. It is plain text rather
              than a document — there is one column behind it — and it is on the page rather
              than behind a tab because a backer deciding whether to commit money is exactly
              who it was written for.
            */
            <section aria-labelledby="campaign-risks" className="flex flex-col gap-3">
              <h2 id="campaign-risks" className="text-xl font-medium tracking-[-0.02em] text-white">
                Risks and challenges
              </h2>
              <p className="max-w-[68ch] text-[1.0625rem] leading-[1.75] whitespace-pre-line text-reading">
                {campaign.risks}
              </p>
            </section>
          )}
        </div>

        <CampaignRewards tiers={tiers} />
      </div>
    </main>
  );
}
