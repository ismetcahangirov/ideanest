import type { Metadata } from 'next';
import { CollectionIndex } from '../../../components/collections/CollectionIndex';
import { StructuredData } from '../../../components/seo/StructuredData';
import { fetchCollections } from '../../../lib/api/server';
import { COLLECTIONS_PATH } from '../../../lib/collections/api';
import { collectionsIndexGraph } from '../../../lib/seo/structured-data/graphs';
import { publicPageMetadata } from '../../../lib/seo/metadata';

/**
 * `/collections` — D-08's index, §4.13 WS-04. Issue #266.
 *
 * <h2>It is the only path to a collection</h2>
 *
 * §4.3 gives curated collections, themed collections and open calls one address each, and
 * before this page there was no link on the site that reached any of them. The `programme`
 * filter on `/v1/discover` narrows to an open call's members, but `lib/discovery/filters.ts`
 * does not expose it and robots.txt disallows `/discover?` wholesale in any case
 * (`lib/seo/indexability.ts`) — so a filter could never have been the answer. This page is the
 * crawl path, the header and footer's destination for curation, and the parent of every
 * collection landing page in the sitemap.
 *
 * <h2>The list is data, not code</h2>
 *
 * Nothing here holds a list of collections. The page renders whatever `GET /v1/collections`
 * answers, in the order a curator arranged, so a collection published by an administrator has
 * a card and a link the moment the read revalidates — sixty seconds, for the reason
 * `fetchCollections` gives.
 *
 * <h2>A static `metadata`, because there is nothing per-request to say</h2>
 *
 * The page takes no parameters and its title and description do not vary with what the service
 * answers. `generateMetadata` here would opt the route out of static metadata resolution to
 * produce the same three strings — `app/(site)/categories/page.tsx` makes the same call for the
 * same reason.
 */

export const metadata: Metadata = publicPageMetadata({
  title: 'Collections',
  description:
    'Staff selections, themed collections, and open calls on IdeaNest — each with its own page of campaigns.',
  path: COLLECTIONS_PATH,
});

export default async function CollectionsPage() {
  const collections = await fetchCollections();

  return (
    <>
      <StructuredData nodes={collectionsIndexGraph()} />
      <CollectionIndex collections={collections} />
    </>
  );
}
