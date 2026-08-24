'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  EmptyState,
  Field,
  InlineAlert,
  Pill,
  Skeleton,
  SkeletonGroup,
  Tag,
  TextInput,
} from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import {
  COLLECTION_KIND_LABELS,
  addProject,
  collectionTitle,
  isPublished,
  readCollection,
  removeProject,
  reorderProjects,
  type AdminCollection,
  type CollectionMember,
} from '../../lib/admin/curation';
import {
  consoleMessageFor,
  statusFor,
  wasAborted,
  type ConsoleStatus,
} from '../../lib/admin/refusals';
import { ConsoleRefusal } from './ConsoleRefusal';
import { NoteDialog } from './NoteDialog';

const SUBJECT = 'this collection';

export interface CollectionEditorProps {
  readonly slug: string;
}

/**
 * §4.11's AD-03: one collection and the campaigns in it — issues #301 and #303.
 *
 * <h2>Membership is the only thing edited here that a reader ever sees directly</h2>
 *
 * A collection's copy, kind and window are what it is; its membership is what it contains, and
 * on a badge-granting collection <strong>adding a campaign is applying §3.2's editorial
 * badge</strong>. That is why the add and the remove go through a required note and a dialog,
 * where the placement editor's move does not: one is a decision about which campaigns the
 * platform stands behind, the other is which order six lists appear in.
 *
 * <h2>Campaigns the public cannot see are shown, and marked</h2>
 *
 * The admin projection returns membership rows whose campaign has since been suspended or
 * unpublished. Hiding those would leave a curator unable to remove the one thing they came to
 * remove — and a collection whose visible count is lower than its membership is exactly the
 * situation somebody is investigating when they open this page.
 *
 * <h2>Reordering restates the whole sequence</h2>
 *
 * `PUT …/projects/order` takes every campaign in the collection exactly once, because a
 * partial order would leave the rest wherever they happened to be. So a move sends the full
 * list with two entries swapped, which is also what makes a move one request here and two on
 * the placement editor.
 *
 * <h2>Nothing is optimistic</h2>
 *
 * Every response is the collection as the service now holds it, and it replaces local state
 * wholesale. The same rule the moderation queue and the account directory follow: these are
 * audited changes another curator may be making at the same moment.
 */
export function CollectionEditor({ slug }: CollectionEditorProps) {
  const [status, setStatus] = useState<ConsoleStatus>('loading');
  const [collection, setCollection] = useState<AdminCollection | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  const [projectId, setProjectId] = useState('');
  const [pending, setPending] = useState<
    { readonly kind: 'add'; readonly projectId: string } | { readonly kind: 'remove'; readonly member: CollectionMember } | null
  >(null);
  const [dialogBusy, setDialogBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const [reordering, setReordering] = useState(false);

  useEffect(() => {
    const controller = new AbortController();

    async function load(): Promise<void> {
      setStatus('loading');
      setNotFound(false);
      try {
        const loaded = await readCollection(slug, controller.signal);
        if (controller.signal.aborted) return;

        setCollection(loaded);
        setError(null);
        setStatus('ready');
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;

        if (cause instanceof ApiError && cause.status === 404) {
          setNotFound(true);
          setStatus('ready');
          return;
        }
        const next = statusFor(cause);
        if (next === 'failed') setError(consoleMessageFor(cause, SUBJECT));
        setStatus(next);
      }
    }

    void load();
    return () => controller.abort();
  }, [slug, attempt]);

  const move = useCallback(
    async (index: number, direction: -1 | 1): Promise<void> => {
      const members = collection?.projects;
      if (collection === undefined || collection === null || members === undefined || reordering) {
        return;
      }

      const ids = members.map((member) => member.projectId);
      const from = ids[index];
      const to = ids[index + direction];
      if (from === undefined || to === undefined) return;

      const next = [...ids];
      next[index] = to;
      next[index + direction] = from;

      setReordering(true);
      setError(null);
      try {
        setCollection(await reorderProjects(collection.slug, next));
      } catch (cause) {
        setError(consoleMessageFor(cause, SUBJECT));
      } finally {
        setReordering(false);
      }
    },
    [collection, reordering],
  );

  if (status === 'signed-out' || status === 'forbidden') {
    return <ConsoleRefusal status={status} subject={SUBJECT} />;
  }

  if (status === 'loading') {
    return (
      <SkeletonGroup label="Loading the collection">
        <div className="rounded-xl border border-white/8 bg-surface-1 p-5">
          <Skeleton height="1.25rem" width="40%" />
          <Skeleton height="0.875rem" width="60%" className="mt-3" />
        </div>
      </SkeletonGroup>
    );
  }

  if (notFound) {
    return (
      <EmptyState
        variant="empty"
        title="No such collection"
        description="That handle does not name a collection. Handles are permanent, so this is a wrong link rather than a rename."
        action={
          <Link
            href="/admin/curation"
            className="rounded-lg text-sm text-white/64 underline underline-offset-2 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            Back to the collections
          </Link>
        }
      />
    );
  }

  if (collection === null) {
    return (
      <>
        {error !== null && (
          <InlineAlert variant="danger" title="Something went wrong">
            {error}
          </InlineAlert>
        )}
        <Pill variant="ghost" size="sm" className="mt-4" onClick={() => setAttempt((n) => n + 1)}>
          Try again
        </Pill>
      </>
    );
  }

  const members = collection.projects ?? [];
  const hidden = members.filter((member) => !member.publiclyVisible).length;

  async function commit(note: string): Promise<void> {
    if (pending === null || collection === null) return;

    setDialogBusy(true);
    setDialogError(null);
    setError(null);
    try {
      if (pending.kind === 'add') {
        setCollection(await addProject(collection.slug, pending.projectId, note));
        setProjectId('');
        setNotice('The campaign is in the collection, at the end of it.');
      } else {
        setCollection(await removeProject(collection.slug, pending.member.projectId, note));
        setNotice(`${pending.member.title} is out of the collection, and its badge with it.`);
      }
      setPending(null);
    } catch (cause) {
      setDialogError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setDialogBusy(false);
    }
  }

  return (
    <div>
      <section
        aria-labelledby="collection-heading"
        className="rounded-xl border border-white/8 bg-surface-1 p-5"
      >
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h2
              id="collection-heading"
              className="text-lg font-medium tracking-[-0.02em] text-white"
            >
              {collectionTitle(collection)}
            </h2>
            <p className="mt-1 font-mono text-xs text-white/40">
              /collections/{collection.slug}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Tag>{COLLECTION_KIND_LABELS[collection.kind]}</Tag>
            {collection.grantsBadge && <Tag variant="warning">Grants the badge</Tag>}
            <Tag variant={isPublished(collection) ? 'success' : 'default'}>
              {isPublished(collection) ? 'Published' : 'Unpublished'}
            </Tag>
          </div>
        </div>

        <dl className="mt-4 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <div className="flex gap-2">
            <dt className="text-white/40">Placement</dt>
            <dd className="font-mono text-white/64">{collection.sortOrder}</dd>
          </div>
          <div className="flex gap-2">
            <dt className="text-white/40">Languages</dt>
            {/*
              Which locales have copy, rather than the copy itself. §21.1's chain falls back
              to Azerbaijani and then to the slug, so a collection with only English copy
              renders as a handle for most readers — a fact worth seeing at a glance, and one
              that a form full of text areas would bury.
            */}
            <dd className="text-white/64">{Object.keys(collection.copy).join(', ') || 'none'}</dd>
          </div>
        </dl>
      </section>

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

      <section aria-labelledby="members-heading" className="mt-8">
        <h2 id="members-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Campaigns
          <span className="ml-2 text-xs font-normal text-white/40">{members.length}</span>
        </h2>
        {hidden > 0 && (
          <p className="mt-1 max-w-[62ch] text-sm text-white/48">
            {hidden === 1 ? 'One campaign here is' : `${hidden} campaigns here are`} not visible
            to the public — suspended, or no longer published. The membership row stays; the
            campaign stops being counted.
          </p>
        )}

        <form
          className="mt-4 flex flex-wrap items-end gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            const trimmed = projectId.trim();
            if (trimmed === '') return;
            setDialogError(null);
            setPending({ kind: 'add', projectId: trimmed });
          }}
        >
          <Field
            label="Add a campaign"
            hint="Its identifier. It goes to the end of the list."
            className="min-w-[280px] flex-1"
          >
            <TextInput
              value={projectId}
              onChange={(event) => setProjectId(event.target.value)}
              placeholder="00000000-0000-0000-0000-000000000000"
            />
          </Field>
          <Pill type="submit" variant="outline" size="sm" className="mb-1">
            Add
          </Pill>
        </form>

        {members.length === 0 ? (
          <EmptyState
            className="mt-4"
            variant="empty"
            title="Nothing in it yet"
            description="A published collection with nothing in it is a page that says nothing. Add campaigns before publishing."
          />
        ) : (
          <ol className="mt-4 flex list-none flex-col gap-2">
            {members.map((member, index) => (
              <li
                key={member.projectId}
                className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-white/8 bg-surface-1 p-4"
              >
                <div className="min-w-0">
                  <p className="text-[15px] text-white">{member.title}</p>
                  <p className="mt-1 font-mono text-xs text-white/40">{member.slug}</p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  {!member.publiclyVisible && <Tag variant="warning">{member.state}</Tag>}
                  <Pill
                    variant="outline"
                    size="sm"
                    disabled={reordering || index === 0}
                    aria-label={`Move up: ${member.title}`}
                    onClick={() => void move(index, -1)}
                  >
                    Move up
                  </Pill>
                  <Pill
                    variant="outline"
                    size="sm"
                    disabled={reordering || index === members.length - 1}
                    aria-label={`Move down: ${member.title}`}
                    onClick={() => void move(index, 1)}
                  >
                    Move down
                  </Pill>
                  <Pill
                    variant="ghost"
                    size="sm"
                    aria-label={`Remove ${member.title} from this collection`}
                    onClick={() => {
                      setDialogError(null);
                      setPending({ kind: 'remove', member });
                    }}
                  >
                    Remove
                  </Pill>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>

      <NoteDialog
        title={
          pending === null
            ? null
            : pending.kind === 'add'
              ? 'Add this campaign?'
              : `Remove ${pending.member.title}?`
        }
        description={
          pending?.kind === 'add'
            ? 'It goes to the end of the list.'
            : 'It leaves the collection. Nothing about the campaign itself changes.'
        }
        body={
          collection.grantsBadge
            ? pending?.kind === 'add'
              ? 'This collection grants the editorial badge, so adding a campaign here is applying it.'
              : 'This collection grants the editorial badge, so removing a campaign takes its badge with it.'
            : undefined
        }
        confirmLabel={pending?.kind === 'add' ? 'Add' : 'Remove'}
        busyLabel={pending?.kind === 'add' ? 'Adding' : 'Removing'}
        destructive={pending?.kind === 'remove'}
        busy={dialogBusy}
        error={dialogError}
        onCancel={() => {
          setPending(null);
          setDialogError(null);
        }}
        onConfirm={(note) => void commit(note)}
      />
    </div>
  );
}
