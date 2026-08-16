'use client';

import { SkeletonCard, SkeletonGroup } from '@ideanest/ui';

/**
 * The placeholder grid, shown while the first page of results is in flight.
 *
 * ONE COMPONENT FOR TWO WAITS. `/discover` suspends on `useSearchParams` before
 * `DiscoveryView` mounts, and then the view waits again for its own request.
 * Two different loading states across that boundary would make the page appear
 * to reload; the same grid across both makes the transition invisible.
 *
 * `'use client'`, and that is not decoration either. `@ideanest/ui` is a barrel
 * whose members include `createContext` consumers, so importing it from a
 * Server Component pulls client-only modules into the server graph and
 * `next build` refuses the whole route. The boundary belongs here rather than
 * on the page, so the page stays a plain server component.
 *
 * The placeholders are `aria-hidden` inside an `aria-busy` container carrying
 * the real message: a grey rectangle has no accessible name worth announcing
 * (docs/ui-kit.md §7.15). The shimmer is the one animation Discovery's motion
 * budget sanctions, and it is `transform` only.
 */

const CARDS = Array.from({ length: 6 }, (_, index) => index);

export function DiscoverySkeleton() {
  return (
    <SkeletonGroup label="Loading projects">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {CARDS.map((index) => (
          <SkeletonCard key={index} />
        ))}
      </div>
    </SkeletonGroup>
  );
}
