import type { Metadata } from 'next';
import { Suspense } from 'react';
import { DiscoverySkeleton } from '../../components/discovery/DiscoverySkeleton';
import { DiscoveryView } from '../../components/discovery/DiscoveryView';

export const metadata: Metadata = {
  title: 'Discover',
  description: 'Browse and filter every campaign on IdeaNest.',
};

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
 */
export default function DiscoverPage() {
  return (
    <Suspense
      fallback={
        <div className="mx-auto w-full max-w-[1400px] px-5 py-10 sm:px-6">
          <DiscoverySkeleton />
        </div>
      }
    >
      <DiscoveryView />
    </Suspense>
  );
}
