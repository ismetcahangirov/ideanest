'use client';

import { useState } from 'react';
import Link from 'next/link';
import { EmptyState, InlineAlert, Pill } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import { campaignCount, getCollectionCampaigns } from '../../lib/collections/api';
import type { ProjectCard } from '../../lib/discovery/api';
import { CampaignGrid } from '../browse/CampaignGrid';

/**
 * A collection's campaigns, in the curator's order — D-08, §4.13 WS-04.
 *
 * <h2>Why this pages instead of handing the reader off to the feed</h2>
 *
 * `/categories/{slug}` and `/search` both show one page of results and then offer the same
 * query inside `/discover`, where the paging machinery already lives. Neither argument
 * survives here, and the reason is the one thing a curated list is: **the order**. It was
 * arranged by a person, row by row, and there is no filter on `/v1/discover` that reproduces
 * it — `?programme={slug}` narrows to an open call's members and then sorts them by whatever
 * the feed sorts by. Sending a reader to the feed for "the rest of this collection" would be
 * sending them to a different list with the same members.
 *
 * So it pages here, on the pattern `ProfileCampaignGrid` established: the first page comes
 * from the server render and the browser only ever asks for what comes after it.
 *
 * <h2>Seeded by the server, and never refetching the first page</h2>
 *
 * `app/(site)/collections/[slug]/page.tsx` fetched page one so that the campaigns are in the
 * HTML a crawler and a slow connection receive — this route exists to be indexed, and a grid
 * the browser assembles is a grid a crawler never sees. Handing that page to a hook that reads
 * on mount would mean requesting the same twenty-four rows a second time on every visit and
 * briefly rendering a skeleton over content already on screen. `getCollectionCampaigns` takes
 * a cursor and no null, so page one is not something this component can be talked into asking
 * for.
 *
 * <h2>A button, not an infinite scroll</h2>
 *
 * The feed's sentinel is right for an unbounded list somebody is grazing. A collection is a
 * bounded, edited sequence with a stated size in its own header, and the reader arrived to see
 * a specific list rather than to browse — an explicit control is also the one a keyboard reader
 * can operate at all, which a scroll sentinel is not. The button names the collection so that
 * two "Show more" controls in one document are two controls a screen reader can tell apart
 * (docs/ui-kit.md §9.4).
 *
 * <h2>A failed page does not destroy the pages already read</h2>
 *
 * An append that fails leaves the items and the cursor alone and reports the refusal beside
 * the button. A reader who has loaded four pages keeps four pages; blanking a list somebody is
 * reading is a worse answer than saying the next page did not arrive — the rule
 * `useDiscoveryFeed` states for the same failure.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §8 forbids animation in long lists, and appended pages are exactly the
 * case it names. The button changes its label while it waits; nothing moves.
 */

function messageFor(cause: unknown): string {
  if (cause instanceof ApiError) {
    return (
      cause.problem?.detail ?? cause.problem?.title ?? 'The service refused the request. Try again.'
    );
  }
  return 'The service could not be reached. Check your connection and try again.';
}

export interface CollectionCampaignsProps {
  readonly slug: string;
  /** The collection's own name, for the empty state and the button's accessible name. */
  readonly title: string;
  /** The first page, from the server render, in the curator's order. */
  readonly initial: readonly ProjectCard[];
  /** The token for the page after the first, or `null` when there is not one. */
  readonly initialCursor: string | null;
}

export function CollectionCampaigns({
  slug,
  title,
  initial,
  initialCursor,
}: CollectionCampaignsProps) {
  const [items, setItems] = useState<readonly ProjectCard[]>(initial);
  const [cursor, setCursor] = useState<string | null>(initialCursor);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function loadMore(): Promise<void> {
    if (cursor === null || loading) return;

    setLoading(true);
    setError(null);
    try {
      const page = await getCollectionCampaigns(slug, cursor);
      setItems((previous) => [...previous, ...page.items]);
      setCursor(page.nextCursor);
    } catch (cause) {
      setError(messageFor(cause));
    } finally {
      setLoading(false);
    }
  }

  if (items.length === 0) {
    return (
      /*
        `empty`, not `filtered`. A collection with nothing publicly visible in it is a real
        state — every campaign a curator chose may be unlaunched, or one may have been
        suspended since — and nothing about it is a filter the reader applied.
      */
      <EmptyState
        variant="empty"
        headingLevel={2}
        title="Nothing to show here yet"
        description={`No campaign in ${title} is published at the moment. The feed carries every campaign on the platform.`}
        action={
          <Link
            href="/discover"
            className="inline-flex h-10 items-center rounded-full bg-white px-5 text-sm font-medium text-on-white transition-colors duration-150 ease-in-out hover:bg-[var(--white-muted)]"
          >
            Browse the feed
          </Link>
        }
      />
    );
  }

  return (
    <div className="flex flex-col gap-8">
      {/*
        The count as text, before the grid. A list whose only statement of how many are loaded
        is the number of cards on screen is one a screen-reader user has to count. It says how
        many are SHOWN rather than how many exist — the header states the collection's own
        total, and a figure here that silently meant "the first twenty-four" would be a wrong
        number a reader has no way to tell from a right one.
      */}
      <p className="text-sm text-white/64 tabular-nums">
        {campaignCount(items.length)} shown
        {cursor === null ? '' : ', with more to load'}
      </p>

      <CampaignGrid campaigns={items} priorityCount={3} label={`Campaigns in ${title}`} />

      {cursor !== null && (
        <div className="flex justify-center">
          <Pill
            type="button"
            variant="outline"
            disabled={loading}
            aria-label={`Show more campaigns in ${title}`}
            onClick={() => void loadMore()}
          >
            {loading ? 'Loading' : 'Show more'}
          </Pill>
        </div>
      )}

      {error !== null && (
        <InlineAlert variant="danger" title="The next page did not load">
          <p>{error}</p>
        </InlineAlert>
      )}
    </div>
  );
}
