import type { Metadata } from 'next';
import { Suspense } from 'react';
import { DiscoverySkeleton } from '../../components/discovery/DiscoverySkeleton';
import { DiscoveryView } from '../../components/discovery/DiscoveryView';
import { StructuredData } from '../../components/seo/StructuredData';
import { publicPageMetadata } from '../../lib/seo/metadata';
import { discoverPageGraph } from '../../lib/seo/structured-data/graphs';

/**
 * `/discover`, and every filtered variant of it, is one canonical URL.
 *
 * **A STATIC `metadata`, NOT `generateMetadata`.** Reading `searchParams` in
 * `generateMetadata` would let this page emit a per-filter canonical — and it opts
 * the route out of static rendering. That was measured rather than assumed: with
 * it, `next build` prints `/discover` as `ƒ (Dynamic)` instead of `○ (Static)`, so
 * the front door would be server-rendered on every request in exchange for a
 * canonical tag.
 *
 * It would also be the wrong tag. The filters live in the query string and the
 * feed they select is fetched in the browser (`DiscoveryView`), so every filter
 * combination is served the SAME document; one canonical is what actually
 * happened, and `canonicalUrl` explains the rest.
 */
export const metadata: Metadata = publicPageMetadata({
  title: 'Discover',
  description: 'Browse and filter every campaign on IdeaNest.',
  path: '/discover',
});

/**
 * `/discover` — docs/architecture.md §4.3.
 *
 * THE `Suspense` BOUNDARY IS NOT DECORATION. `DiscoveryView` reads the query
 * string with `useSearchParams`, which Next cannot know at build time; without
 * a boundary the whole route opts out of static rendering and `next build`
 * fails the page outright. The fallback is the same skeleton grid the view
 * shows while its first request is in flight, so the transition between the two
 * is invisible rather than a second, different loading state.
 *
 * NOTHING FROM `@ideanest/ui` IS IMPORTED HERE. It is a barrel, and several of
 * its members consume `createContext`; reaching it from a Server Component
 * pulls client-only modules into the server graph and the build refuses the
 * route. Both children below carry their own `'use client'`.
 *
 * **THE SITE'S IDENTITY IS CLAIMED HERE**, outside the `Suspense` boundary.
 * `/discover` is the front door — `app/page.tsx` does not exist yet — and
 * `lib/seo/structured-data/identity.ts` explains why the `Organization` and
 * `WebSite` nodes belong on the entry page rather than in the root layout. It
 * sits outside the boundary because it depends on nothing the query string
 * decides, so it must be in the first byte a crawler is served rather than in a
 * streamed chunk that arrives after the fallback.
 */
export default function DiscoverPage() {
  return (
    <>
      <StructuredData nodes={discoverPageGraph()} />
      <Suspense
        fallback={
          <div className="mx-auto w-full max-w-[1400px] px-5 py-10 sm:px-6">
            <DiscoverySkeleton />
          </div>
        }
      >
        <DiscoveryView />
      </Suspense>
    </>
  );
}
