'use client';

import { useState } from 'react';
import { Link } from '../../i18n/navigation';
import { EmptyState, InlineAlert, Pill, Skeleton, SkeletonGroup, Tag } from '@ideanest/ui';
import {
  COLLECTION_KIND_LABELS,
  bodyOf,
  collectionTitle,
  isPublished,
  replaceCollection,
} from '../../lib/admin/curation';
import { consoleMessageFor } from '../../lib/admin/refusals';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useCollections } from './useCollections';

const SUBJECT = 'the placement';

/**
 * §4.11's AD-03: where a curated collection appears — issue #303.
 *
 * <h2>What placement is, given that there is no placement table</h2>
 *
 * §4.13's WS-04 makes the home page "the only page whose content is entirely editorial", and
 * AD-03 is what decides it. The mechanism is one integer: `collections.sort_order`, which
 * orders the collections index and the rails built from it. Lower is earlier, and the
 * published ones are what a visitor meets.
 *
 * <p>That is less than the issue's title suggests, and the screen says so rather than
 * implying a slot editor that does not exist. There is no endpoint that puts a named
 * collection in a named position on a named page — placement today is the order of one list —
 * and inventing a richer interface over an integer would be an interface that stops working
 * the moment somebody builds the real thing.
 *
 * <h2>Move up and move down, not a number to type</h2>
 *
 * A curator's question is "put this above that", not "what integer is that". Two buttons make
 * that expressible without anybody having to know what the current numbers are, or that they
 * are allowed to have gaps.
 *
 * <p><strong>A move is two requests, and the screen says when it has done one of them.</strong>
 * The endpoint replaces one collection at a time, so swapping two positions is two `PUT`s.
 * They are sent in sequence and the second failing leaves the first applied — which is a real
 * state, not a bug to hide: the list is then in an order somebody did not ask for, and the
 * honest response is to say so and re-read rather than to guess at a rollback the API cannot
 * make atomic.
 *
 * <h2>Unpublished collections are shown and are not hidden from the order</h2>
 *
 * They occupy a position and will take it the moment they are published. A placement editor
 * that showed only the published ones would let somebody arrange six rails and then be
 * surprised by a seventh.
 *
 * <h2>Motion: none</h2>
 *
 * The rows do not animate as they move. docs/motion-system.md §8 rules out animation in lists,
 * and a reorder that slid would be 300ms in which the next button cannot be aimed at — on a
 * screen whose whole interaction is pressing the same button repeatedly.
 */
export function PlacementEditor() {
  const { status, collections, error, reload, setError } = useCollections();
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  if (status === 'signed-out' || status === 'forbidden') {
    return <ConsoleRefusal status={status} subject={SUBJECT} />;
  }

  /*
   * Sorted by `sortOrder` and then by slug. The service returns them in placement order
   * already; the tie-break is here because two collections may share a number — nothing
   * makes it unique — and a list whose order changed between renders for two equal rows
   * would move under somebody pressing a button.
   */
  const ordered = [...collections].sort(
    (a, b) => a.sortOrder - b.sortOrder || a.slug.localeCompare(b.slug),
  );

  async function swap(index: number, direction: -1 | 1): Promise<void> {
    const first = ordered[index];
    const second = ordered[index + direction];
    if (first === undefined || second === undefined || busy) return;

    setBusy(true);
    setError(null);
    try {
      await replaceCollection(first.slug, { ...bodyOf(first), sortOrder: second.sortOrder });
      await replaceCollection(second.slug, { ...bodyOf(second), sortOrder: first.sortOrder });
      setNotice(`${collectionTitle(first)} moved ${direction === -1 ? 'up' : 'down'}.`);
    } catch (cause) {
      setError(
        `${consoleMessageFor(cause, SUBJECT)} A move is two requests; if the first succeeded, the order below is what the service now holds.`,
      );
    } finally {
      setBusy(false);
      /*
       * Re-read either way. On success because both numbers changed, on failure because the
       * screen does not know how far it got — and a placement editor showing an order the
       * service does not have is the one failure this screen must not have.
       */
      reload();
    }
  }

  return (
    <section aria-labelledby="placement-heading">
      <h2 id="placement-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
        Placement order
        {status === 'ready' && (
          <span className="ml-2 text-xs font-normal text-white/40">{ordered.length}</span>
        )}
      </h2>
      <p className="mt-1 max-w-[62ch] text-sm text-white/48">
        Lower is earlier. This is the order of the collections index and of the rails built
        from it — one integer per collection, which is the whole of what placement means today.
      </p>

      <div role="status" aria-live="polite" className="empty:hidden">
        {notice !== null && (
          <InlineAlert variant="success" className="mt-4">
            {notice}
          </InlineAlert>
        )}
      </div>

      {error !== null && (
        <InlineAlert variant="danger" title="The order may not be what you asked for" className="mt-4">
          {error}
        </InlineAlert>
      )}

      {status === 'loading' && (
        <SkeletonGroup label="Loading the placement order" className="mt-4">
          <div className="space-y-2">
            {[0, 1, 2].map((row) => (
              <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                <Skeleton height="1rem" width="40%" />
              </div>
            ))}
          </div>
        </SkeletonGroup>
      )}

      {status === 'ready' && ordered.length === 0 && (
        <EmptyState
          className="mt-4"
          variant="empty"
          title="Nothing to place"
          description="There are no collections yet. Create one on the collections screen, and it appears here."
        />
      )}

      {status === 'ready' && ordered.length > 0 && (
        <ol className="mt-4 flex list-none flex-col gap-2">
          {ordered.map((collection, index) => (
            <li
              key={collection.slug}
              className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-white/8 bg-surface-1 p-4"
            >
              <div className="min-w-0">
                <Link
                  href={`/admin/curation/${encodeURIComponent(collection.slug)}`}
                  className="rounded-lg text-[15px] font-medium text-white underline-offset-2 transition-colors duration-150 ease-in-out hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                >
                  {collectionTitle(collection)}
                </Link>
                <p className="mt-1 text-xs text-white/40">
                  <span className="font-mono">{collection.sortOrder}</span>{' '}
                  · {COLLECTION_KIND_LABELS[collection.kind]}
                </p>
              </div>

              <div className="flex items-center gap-2">
                <Tag variant={isPublished(collection) ? 'success' : 'default'}>
                  {isPublished(collection) ? 'Published' : 'Unpublished'}
                </Tag>
                {/*
                  The accessible name says which collection moves. Twelve rows of "Move up" is
                  unusable by ear, and speech input needs the visible word inside the name
                  (WCAG 2.5.3) — which "Move up" is.
                */}
                <Pill
                  variant="outline"
                  size="sm"
                  disabled={busy || index === 0}
                  aria-label={`Move up: ${collectionTitle(collection)}`}
                  onClick={() => void swap(index, -1)}
                >
                  Move up
                </Pill>
                <Pill
                  variant="outline"
                  size="sm"
                  disabled={busy || index === ordered.length - 1}
                  aria-label={`Move down: ${collectionTitle(collection)}`}
                  onClick={() => void swap(index, 1)}
                >
                  Move down
                </Pill>
              </div>
            </li>
          ))}
        </ol>
      )}

      {status === 'failed' && (
        <Pill variant="ghost" size="sm" className="mt-4" onClick={reload}>
          Try again
        </Pill>
      )}
    </section>
  );
}
