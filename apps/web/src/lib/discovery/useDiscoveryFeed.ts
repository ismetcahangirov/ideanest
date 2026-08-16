'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../api/problem';
import { getDiscoveryFeed, type ProjectCard } from './api';
import { filterKey, type DiscoveryFilters } from './filters';

/**
 * D-04's cursor-paginated feed, as a hook.
 *
 * WHY A CURSOR AND NOT AN OFFSET. `pledged_amount` moves continuously on a
 * funding platform, so a campaign promoted from the bottom of the order to the
 * top shifts every row below it — and under `OFFSET` the next page then returns
 * a card the reader has already seen. A keyset cursor shows a row that moves
 * once or not at all. The server's `DiscoveryApiTests` has the test that
 * justifies the design; this hook's job is not to undo it.
 *
 * THE END OF THE FEED IS AN ABSENT CURSOR, never a short page. A page that
 * comes back with fewer cards than were asked for is still followed by more
 * whenever `nextCursor` is present, and a client that stopped on a short page
 * would truncate a feed whose last page happened to be full.
 *
 * ONE REQUEST PER CURSOR, ENFORCED. A sentinel at the bottom of a grid crosses
 * the viewport repeatedly — a scroll that overshoots and settles, a window
 * resize, an image loading above it and pushing it back down — and every
 * crossing fires the observer again. Without the guard below the same page is
 * fetched three times and appended three times, which reads as duplicated
 * cards. Cursors that have been requested are remembered rather than merely
 * "am I loading right now", because the second crossing routinely arrives
 * between the response landing and React committing the state it produced.
 */

export type FeedStatus = 'loading' | 'ready' | 'failed';

export interface DiscoveryFeedState {
  readonly items: readonly ProjectCard[];
  /**
   * The FIRST page's outcome, and only that.
   *
   * A page that fails part-way down the scroll does not move this to `failed`,
   * because the cards already on screen are still true and blanking a feed
   * somebody is reading is a worse answer than saying the next page did not
   * arrive. That failure is reported through `error` while the status stays
   * `ready`.
   */
  readonly status: FeedStatus;
  /** The RFC 9457 problem the service answered with, for the caller to render. */
  readonly error: ApiError | null;
  /** True when there is another page. Absent `nextCursor` is the only signal. */
  readonly hasMore: boolean;
  readonly loadingMore: boolean;
  /** What to put in the polite live region. Empty until a page has settled. */
  readonly announcement: string;
  readonly loadMore: () => void;
  readonly retry: () => void;
}

function wasAborted(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

export function useDiscoveryFeed(filters: DiscoveryFilters): DiscoveryFeedState {
  const key = filterKey(filters);

  const [items, setItems] = useState<readonly ProjectCard[]>([]);
  const [status, setStatus] = useState<FeedStatus>('loading');
  const [error, setError] = useState<ApiError | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [announcement, setAnnouncement] = useState('');

  /**
   * Every cursor a request has already been sent for, including the sentinel
   * for the first page. Reset when the query changes, because a cursor issued
   * for one filter set is refused by another (`DISCOVERY_CURSOR_MISMATCH`) and
   * the pages held are no longer answers to the question being asked.
   */
  const requested = useRef<Set<string>>(new Set());

  /**
   * Bumped to re-run the first-page effect for the same filters — what "try
   * again" does after a failure. Without it a retry would have to fake a change
   * to the query, which would put a filter in the URL nobody chose.
   */
  const [attempt, setAttempt] = useState(0);

  /**
   * The filters `loadMore` will send, read through a ref.
   *
   * `filters` is rebuilt from the URL on every render, so closing over it
   * directly would give `loadMore` a new identity every time — and the
   * `IntersectionObserver` that calls it would be torn down and rebuilt on
   * every render with it. The value is the same value either way: the effect
   * above has already reset everything whenever the filters changed.
   */
  const latestFilters = useRef(filters);
  latestFilters.current = filters;

  useEffect(() => {
    const controller = new AbortController();

    requested.current = new Set(['']);
    setItems([]);
    setStatus('loading');
    setError(null);
    setCursor(null);
    setLoadingMore(false);

    void (async () => {
      try {
        const page = await getDiscoveryFeed(latestFilters.current, { signal: controller.signal });
        if (controller.signal.aborted) return;

        setItems(page.items);
        setCursor(page.nextCursor ?? null);
        setStatus('ready');
        setAnnouncement(
          page.items.length === 0
            ? 'No projects match these filters.'
            : `${plural(page.items.length, 'project', 'projects')} shown.`,
        );
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;

        setStatus('failed');
        setError(cause instanceof ApiError ? cause : null);
        setAnnouncement('The projects could not be loaded.');
      }
    })();

    return () => controller.abort();
    /*
     * Keyed on the SERIALISED filters rather than the object. `filters` is
     * rebuilt on every render from the URL, so an object identity check would
     * refetch the first page on every keystroke anywhere on the page. The key
     * covers everything that decides which campaigns come back and in what
     * order — the same rule the server's own fingerprint follows.
     */
  }, [key, attempt]);

  const loadMore = useCallback(() => {
    const next = cursor;
    if (next === null || next === '') return;
    if (requested.current.has(next)) return;
    requested.current.add(next);

    setLoadingMore(true);
    setError(null);

    void (async () => {
      try {
        const page = await getDiscoveryFeed(latestFilters.current, { cursor: next });

        setItems((previous) => [...previous, ...page.items]);
        setCursor(page.nextCursor ?? null);
        setAnnouncement(
          `${plural(page.items.length, 'more project', 'more projects')} loaded.`,
        );
      } catch (cause) {
        if (wasAborted(cause)) return;

        /*
         * A failure part-way down does not throw away what is on screen. The
         * cards already read are still the truth; what failed is the next page,
         * and the recovery is to press the control again — so the cursor is
         * released from the requested set rather than left claimed by a request
         * that produced nothing.
         */
        requested.current.delete(next);
        setError(cause instanceof ApiError ? cause : null);
        setAnnouncement('The next page could not be loaded.');
      } finally {
        setLoadingMore(false);
      }
    })();
  }, [cursor]);

  const retry = useCallback(() => {
    setAttempt((n) => n + 1);
  }, []);

  return useMemo(
    () => ({
      items,
      status,
      error,
      hasMore: cursor !== null && cursor !== '',
      loadingMore,
      announcement,
      loadMore,
      retry,
    }),
    [items, status, error, cursor, loadingMore, announcement, loadMore, retry],
  );
}
