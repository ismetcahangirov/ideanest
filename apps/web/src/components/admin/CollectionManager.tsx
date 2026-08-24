'use client';

import { useState } from 'react';
import Link from 'next/link';
import {
  EmptyState,
  Field,
  InlineAlert,
  Pill,
  Select,
  Skeleton,
  SkeletonGroup,
  Tag,
  TextInput,
} from '@ideanest/ui';
import {
  COLLECTION_KIND_LABELS,
  collectionTitle,
  createCollection,
  isPublished,
  publishCollection,
  unpublishCollection,
  type AdminCollection,
  type CollectionKind,
} from '../../lib/admin/curation';
import { consoleMessageFor } from '../../lib/admin/refusals';
import { ConsoleRefusal } from './ConsoleRefusal';
import { NoteDialog } from './NoteDialog';
import { useCollections } from './useCollections';

const SUBJECT = 'the collections';

/** What the manager is about to do to one collection, once a note has been typed. */
type Pending = { readonly collection: AdminCollection; readonly action: 'publish' | 'unpublish' };

export interface CollectionManagerProps {
  /**
   * Draws only the collections this returns true for, and says so in the empty state.
   *
   * <p>Absent on `/admin/curation`, which is every collection there is. Set on the three
   * screens that are the same manager asking a narrower question — the badge screen, the open
   * calls, the placement editor — because §4.3's three kinds are one table with a `kind`
   * column, and V14's own header says why: they differ in what the list means to a reader and
   * in nothing else.
   */
  readonly only?: (collection: AdminCollection) => boolean;
  /** What to say when the filter matches nothing, in the screen's own words. */
  readonly emptyTitle?: string;
  readonly emptyDescription?: string;
  /** Offers the create form. Off on the three narrowed screens — see the docblock. */
  readonly allowCreate?: boolean;
}

/**
 * §4.11's AD-03: editorial collections and what is in them — issue #301.
 *
 * <h2>One component, four screens</h2>
 *
 * `/admin/curation` is this with no filter. `/admin/curation/badges` (#300),
 * `/open-calls` (#302) and `/placements` (#303) are this with one, plus whatever each of them
 * adds. That is not a shortcut: V14 put all three kinds of curated list in one table
 * deliberately, because a staff selection, a themed list and an open call have the same slug,
 * copy, publication decision, window, imagery and membership behind them. Four components
 * would have been the same file with a constant changed, and the copy that got the next fix
 * would have been whichever one somebody was looking at.
 *
 * <h2>Creating is here and nowhere else</h2>
 *
 * A new collection is created unpublished and empty, and its <strong>handle is
 * permanent</strong> — it is half of `/collections/{slug}`, a URL the platform will have put
 * in front of people. The form says so before the field rather than after the refusal, and the
 * three narrowed screens do not offer it: creating a collection from the badge screen would
 * mean guessing which kind it is, and getting that wrong is not correctable through this API.
 *
 * <h2>There is no delete, and the screen says why</h2>
 *
 * `curation_events.collection_id` has no `ON DELETE` clause, on purpose: a collection anything
 * has happened to carries the record of it. Withdrawing one unpublishes it — the public gets a
 * 404 and the reasoning stays intact — and that is the whole of the removal path.
 *
 * <h2>Motion</h2>
 *
 * The dialog's 200ms entry and 150ms of colour on a control. docs/motion-system.md §5's budget
 * for a working surface, and §8 rules out animating a list regardless.
 */
export function CollectionManager({
  only,
  emptyTitle,
  emptyDescription,
  allowCreate = false,
}: CollectionManagerProps) {
  const { status, collections, error, apply, reload, setError } = useCollections();

  const [pending, setPending] = useState<Pending | null>(null);
  const [dialogBusy, setDialogBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [slug, setSlug] = useState('');
  const [kind, setKind] = useState<CollectionKind>('staff_selection');
  const [title, setTitle] = useState('');
  const [creating, setCreating] = useState(false);

  if (status === 'signed-out' || status === 'forbidden') {
    return <ConsoleRefusal status={status} subject={SUBJECT} />;
  }

  const visible = only === undefined ? collections : collections.filter(only);

  async function commit(note: string): Promise<void> {
    if (pending === null) return;

    setDialogBusy(true);
    setDialogError(null);
    setError(null);
    try {
      const updated =
        pending.action === 'publish'
          ? await publishCollection(pending.collection.slug, note)
          : await unpublishCollection(pending.collection.slug, note);

      apply(updated);
      setNotice(
        pending.action === 'publish'
          ? `${collectionTitle(updated)} is now visible to the public.`
          : `${collectionTitle(updated)} has been taken down. The public gets a 404; the record stays.`,
      );
      setPending(null);
    } catch (cause) {
      // The dialog stays open with the note still in it: nothing changed, and retyping a
      // reason is the last thing a refused curator should have to do.
      setDialogError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setDialogBusy(false);
    }
  }

  async function create(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    const handle = slug.trim();
    const heading = title.trim();
    if (handle === '' || heading === '') return;

    setCreating(true);
    setError(null);
    try {
      /*
       * `az` and not `en`. §21.1 makes Azerbaijani the platform's own language and the
       * taxonomy's resolution chain falls back to it, so a collection whose only copy is
       * English resolves to its slug for every reader who has not asked for English.
       */
      await createCollection(handle, { kind, copy: { az: { title: heading } } });
      setSlug('');
      setTitle('');
      setNotice(`${heading} exists, unpublished and empty. Add campaigns, then publish it.`);
      reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setCreating(false);
    }
  }

  return (
    <section aria-labelledby="collections-heading">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="collections-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Collections
          {status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">{visible.length}</span>
          )}
        </h2>
      </div>

      <div role="status" aria-live="polite" className="empty:hidden">
        {notice !== null && (
          <InlineAlert variant="success" className="mt-4">
            {notice}
          </InlineAlert>
        )}
      </div>

      {error !== null && (
        <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
          {error}
        </InlineAlert>
      )}

      {allowCreate && (
        <form
          onSubmit={(event) => void create(event)}
          className="mt-6 rounded-xl border border-white/8 bg-surface-1 p-4 sm:p-5"
        >
          <h3 className="text-sm font-medium text-white">New collection</h3>
          <p className="mt-1 max-w-[62ch] text-sm text-white/48">
            Created unpublished and empty, so nothing is visible until it is worth seeing. The
            handle is half of its public URL and cannot be changed afterwards.
          </p>

          <div className="mt-4 flex flex-wrap items-end gap-3">
            <Field label="Handle" required hint="Lower case, hyphenated. Permanent." className="min-w-[220px] flex-1">
              <TextInput
                value={slug}
                onChange={(event) => setSlug(event.target.value)}
                placeholder="autumn-picks"
              />
            </Field>
            <Field label="Title" required hint="In Azerbaijani — the language the chain falls back to." className="min-w-[220px] flex-1">
              <TextInput value={title} onChange={(event) => setTitle(event.target.value)} />
            </Field>
            <Field label="Kind" className="min-w-[200px]">
              <Select
                value={kind}
                onChange={(event) => setKind(event.target.value as CollectionKind)}
              >
                {(Object.keys(COLLECTION_KIND_LABELS) as CollectionKind[]).map((option) => (
                  <option key={option} value={option}>
                    {COLLECTION_KIND_LABELS[option]}
                  </option>
                ))}
              </Select>
            </Field>
            <Pill type="submit" variant="outline" size="sm" disabled={creating} className="mb-1">
              {creating ? 'Creating' : 'Create'}
            </Pill>
          </div>
        </form>
      )}

      {status === 'loading' && (
        <SkeletonGroup label="Loading the collections" className="mt-6">
          <div className="space-y-3">
            {[0, 1, 2].map((row) => (
              <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                <Skeleton height="1rem" width="35%" />
                <Skeleton height="0.875rem" width="55%" className="mt-3" />
              </div>
            ))}
          </div>
        </SkeletonGroup>
      )}

      {status === 'ready' && visible.length === 0 && (
        <EmptyState
          className="mt-6"
          variant={only === undefined ? 'empty' : 'filtered'}
          title={emptyTitle ?? 'No collections yet'}
          description={
            emptyDescription ??
            'A collection is a curated list with a public page of its own. Create one, add campaigns to it, then publish it.'
          }
        />
      )}

      {status === 'ready' && visible.length > 0 && (
        <ul className="mt-6 flex list-none flex-col gap-2">
          {visible.map((collection) => (
            <CollectionRow
              key={collection.slug}
              collection={collection}
              onPublish={() => {
                setDialogError(null);
                setPending({ collection, action: 'publish' });
              }}
              onUnpublish={() => {
                setDialogError(null);
                setPending({ collection, action: 'unpublish' });
              }}
            />
          ))}
        </ul>
      )}

      {status === 'failed' && (
        <Pill variant="ghost" size="sm" className="mt-4" onClick={reload}>
          Try again
        </Pill>
      )}

      <NoteDialog
        title={
          pending === null
            ? null
            : pending.action === 'publish'
              ? `Publish ${collectionTitle(pending.collection)}?`
              : `Take ${collectionTitle(pending.collection)} down?`
        }
        description={
          pending?.action === 'publish'
            ? 'The list and its campaigns become visible to everybody.'
            : 'The public page starts answering 404. Nothing is deleted.'
        }
        body={
          pending?.action === 'publish' && pending.collection.grantsBadge
            ? 'This collection grants the editorial badge, so publishing it badges every campaign in it.'
            : undefined
        }
        confirmLabel={pending?.action === 'publish' ? 'Publish' : 'Take it down'}
        busyLabel={pending?.action === 'publish' ? 'Publishing' : 'Taking it down'}
        destructive={pending?.action === 'unpublish'}
        busy={dialogBusy}
        error={dialogError}
        onCancel={() => {
          setPending(null);
          setDialogError(null);
        }}
        onConfirm={(note) => void commit(note)}
      />
    </section>
  );
}

/**
 * One collection in the list.
 *
 * <p>The publication state is a word and a token, never a token alone (docs/ui-kit.md §9.2),
 * and "Unpublished" is drawn neutral rather than in `--danger`: a list somebody is still
 * assembling is the normal state of a new collection, not a fault.
 *
 * <p><strong>The badge grant is called out separately from the kind.</strong> V14 made them
 * two columns on purpose — a staff selection is the usual carrier of §3.2's badge and does not
 * imply it — and a curator who reads "Staff selection" and assumes the badge follows is the
 * person this row exists to correct.
 */
function CollectionRow({
  collection,
  onPublish,
  onUnpublish,
}: {
  readonly collection: AdminCollection;
  readonly onPublish: () => void;
  readonly onUnpublish: () => void;
}) {
  const published = isPublished(collection);

  return (
    <li className="rounded-lg border border-white/8 bg-surface-1 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <Link
            href={`/admin/curation/${encodeURIComponent(collection.slug)}`}
            className="rounded-lg text-[15px] font-medium text-white underline-offset-2 transition-colors duration-150 ease-in-out hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            {collectionTitle(collection)}
          </Link>
          <p className="mt-1 font-mono text-xs text-white/40">/collections/{collection.slug}</p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Tag>{COLLECTION_KIND_LABELS[collection.kind]}</Tag>
          {collection.grantsBadge && <Tag variant="warning">Grants the badge</Tag>}
          <Tag variant={published ? 'success' : 'default'}>
            {published ? 'Published' : 'Unpublished'}
          </Tag>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Pill variant="outline" size="sm" onClick={published ? onUnpublish : onPublish}>
          {published ? 'Take it down' : 'Publish'}
        </Pill>
        <span className="text-xs text-white/32">Placement {collection.sortOrder}</span>
      </div>
    </li>
  );
}
