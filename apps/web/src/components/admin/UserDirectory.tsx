'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  CharacterCount,
  Chip,
  ChipRow,
  EmptyState,
  Field,
  InlineAlert,
  Pill,
  Skeleton,
  SkeletonGroup,
  Tag,
  TextInput,
  Textarea,
} from '@ideanest/ui';
import { Modal } from '@ideanest/ui/motion';
import { ApiError } from '../../lib/api/problem';
import {
  REASON_MAX_CHARACTERS,
  banUser,
  listUsers,
  reinstateUser,
  type AdminUser,
} from '../../lib/admin/api';

type Status = 'loading' | 'ready' | 'failed' | 'signed-out' | 'forbidden';

/**
 * Turns a refusal into something a moderator can act on.
 *
 * Branches on `problem.code` and never on prose, like the moderation queue: two
 * refusals that cannot be told apart would force this screen to match on
 * sentences the service is free to reword.
 */
function messageFor(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 403) {
      return 'Your account is not on the platform moderator list, so the account directory is not yours to read.';
    }

    const code = cause.problem?.code;

    if (code === 'ACCOUNT_NOT_FOUND') {
      return 'That account no longer exists. It may have been deleted since this page was loaded.';
    }
    if (code === 'ACCOUNT_SUSPENSION_REFUSED') {
      return 'An account cannot suspend itself. Ask another moderator.';
    }

    return (
      cause.problem?.detail ?? cause.problem?.title ?? 'The service refused the request. Try again.'
    );
  }
  return 'The service could not be reached. Check your connection and try again.';
}

function wasAborted(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}

function accounts(count: number): string {
  return `${count} ${count === 1 ? 'account' : 'accounts'}`;
}

/** The day, in the reader's locale. An instant to the second says more than anybody needs here. */
function day(instant: string | null): string | null {
  if (instant === null) return null;
  const parsed = new Date(instant);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toLocaleDateString();
}

/**
 * §4.11's AD-04: the account directory.
 *
 * <p>THE BAN IS NOT OPTIMISTIC, deliberately, and for the reason the moderation
 * queue gives about its own decisions: it is privileged, audited, and it ends
 * every session the person holds. A row that flips to "Suspended" before the
 * service answers is an interface that lies for a few hundred milliseconds about
 * something that has just signed somebody out of their account. What comes back
 * is the service's own projection and it replaces the row wholesale.
 *
 * <p>THE SEARCH IS A FORM, not a keystroke handler. Every read of this list is
 * audited — it hands out other people's email addresses — so a request per
 * character would fill the one table with no retention rule with a row per
 * keystroke, and would do it while somebody typed an address.
 *
 * <p>MOTION is the modal's entry and colour on hover. Nothing else:
 * docs/motion-system.md §8 forbids animation in long lists, and this is a list of
 * people's accounts.
 */
export function UserDirectory() {
  const [status, setStatus] = useState<Status>('loading');
  const [term, setTerm] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [suspendedOnly, setSuspendedOnly] = useState(false);
  const [loaded, setLoaded] = useState<readonly AdminUser[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  const [busyIds, setBusyIds] = useState<ReadonlySet<string>>(() => new Set());
  const [pending, setPending] = useState<AdminUser | null>(null);
  const [reason, setReason] = useState('');
  const [dialogBusy, setDialogBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);

  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const controller = new AbortController();

    async function load(): Promise<void> {
      setStatus('loading');
      try {
        const page = await listUsers({
          query: submitted,
          suspendedOnly,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;

        setLoaded(page.users);
        setCursor(page.nextCursor);
        setError(null);
        setStatus('ready');
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;

        if (cause instanceof ApiError && cause.status === 401) {
          setStatus('signed-out');
          return;
        }
        if (cause instanceof ApiError && cause.status === 403) {
          setStatus('forbidden');
          return;
        }
        setError(messageFor(cause));
        setStatus('failed');
      }
    }

    void load();
    return () => controller.abort();
  }, [submitted, suspendedOnly, attempt]);

  const loadMore = useCallback(async (): Promise<void> => {
    if (cursor === null || loadingMore) return;

    setLoadingMore(true);
    setError(null);
    try {
      const page = await listUsers({ query: submitted, suspendedOnly, after: cursor });
      setLoaded((previous) => [...previous, ...page.users]);
      setCursor(page.nextCursor);
    } catch (cause) {
      setError(messageFor(cause));
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, loadingMore, submitted, suspendedOnly]);

  function markBusy(id: string, busy: boolean): void {
    setBusyIds((previous) => {
      const next = new Set(previous);
      if (busy) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** The service's own version of an account, back into the list it came from. */
  function applyUser(updated: AdminUser): void {
    setLoaded((previous) =>
      // A reinstated account has left the "suspended only" list, so it is removed
      // rather than left behind contradicting the filter above it.
      suspendedOnly && !updated.suspended
        ? previous.filter((row) => row.id !== updated.id)
        : previous.map((row) => (row.id === updated.id ? updated : row)),
    );
  }

  async function commitBan(): Promise<void> {
    if (pending === null) return;

    const trimmed = reason.trim();
    if (trimmed === '') {
      setDialogError('Say why. The person is told this, and an appeal is answered from it.');
      return;
    }
    if (Array.from(reason).length > REASON_MAX_CHARACTERS) {
      setDialogError(`A reason may not exceed ${REASON_MAX_CHARACTERS} characters.`);
      return;
    }

    setDialogBusy(true);
    setDialogError(null);
    setError(null);
    markBusy(pending.id, true);

    try {
      const updated = await banUser(pending.id, trimmed);
      applyUser(updated);
      setNotice(`${updated.name} is suspended, and every session they held has been revoked.`);
      setPending(null);
      setReason('');
      // The button that opened the dialog may have gone with its row. Moving
      // focus to the heading puts a keyboard user somewhere that still exists.
      headingRef.current?.focus();
    } catch (cause) {
      // Everything keeps the dialog open with the reason still in it: nothing
      // changed, and retyping it is the last thing a refused moderator should do.
      setDialogError(messageFor(cause));
    } finally {
      setDialogBusy(false);
      markBusy(pending.id, false);
    }
  }

  async function commitReinstatement(user: AdminUser): Promise<void> {
    markBusy(user.id, true);
    setError(null);
    setNotice(null);

    try {
      const updated = await reinstateUser(user.id);
      applyUser(updated);
      setNotice(`${updated.name} can sign in again. Their old sessions are not restored.`);
    } catch (cause) {
      setError(messageFor(cause));
    } finally {
      markBusy(user.id, false);
    }
  }

  if (status === 'signed-out') {
    return (
      <InlineAlert variant="info" title="You are signed out">
        This browser no longer has a session. Sign in again to read the account directory.
      </InlineAlert>
    );
  }

  if (status === 'forbidden') {
    return (
      <InlineAlert variant="info" title="Not a moderator">
        Accounts are searched and suspended by platform staff. Your account is not on the configured
        moderator list.
      </InlineAlert>
    );
  }

  return (
    <section aria-labelledby="admin-users-heading">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2
          id="admin-users-heading"
          ref={headingRef}
          tabIndex={-1}
          className="text-lg font-medium tracking-[-0.02em] text-white"
        >
          Accounts
          {status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">{loaded.length}</span>
          )}
        </h2>
      </div>

      {/*
        A form, so that a search is one request per intention rather than one per
        keystroke — every one of them is audited, and the term is frequently
        somebody's email address.
      */}
      <form
        className="mt-4 flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          setSubmitted(term);
        }}
      >
        <Field
          label="Search"
          hint="An email address, a display name, or a profile slug."
          className="min-w-[260px] flex-1"
        >
          <TextInput
            type="search"
            value={term}
            placeholder="ayan@example.com"
            onChange={(event) => setTerm(event.target.value)}
          />
        </Field>
        <Pill type="submit" variant="ghost" size="sm" className="mb-1">
          Search
        </Pill>
      </form>

      <ChipRow aria-label="Standing" className="mt-3">
        <Chip active={suspendedOnly} onClick={() => setSuspendedOnly((previous) => !previous)}>
          Suspended only
        </Chip>
      </ChipRow>

      <div role="status" aria-live="polite" className="empty:hidden">
        {notice && (
          <InlineAlert variant="success" className="mt-4">
            {notice}
          </InlineAlert>
        )}
      </div>

      {error && (
        <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
          {error}
        </InlineAlert>
      )}

      {status === 'loading' && (
        <SkeletonGroup label="Loading accounts" className="mt-4">
          <div className="space-y-3">
            {[0, 1, 2].map((row) => (
              <div key={row} className="rounded-lg border border-white/8 bg-surface-2 p-5">
                <Skeleton height="1.125rem" width="30%" />
                <Skeleton height="0.875rem" width="45%" className="mt-3" />
              </div>
            ))}
          </div>
        </SkeletonGroup>
      )}

      {status === 'ready' && loaded.length === 0 && (
        <EmptyState
          className="mt-4"
          variant={submitted !== '' || suspendedOnly ? 'filtered' : 'empty'}
          title={
            submitted !== '' || suspendedOnly ? 'No accounts match' : 'No accounts to show'
          }
          description={
            submitted !== '' || suspendedOnly
              ? 'Search matches an address, a display name or a profile slug, and it matches anywhere inside them.'
              : 'Accounts appear here as people register.'
          }
        />
      )}

      {status === 'ready' && loaded.length > 0 && (
        <ul className="mt-4 space-y-3">
          {loaded.map((user) => (
            <li
              key={user.id}
              className="rounded-lg border border-white/8 bg-surface-2 p-5"
              aria-busy={busyIds.has(user.id)}
            >
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium text-white">{user.name}</p>
                  {/* The address is why this screen exists and why every read of it
                      is audited. Nothing else on the platform shows one. */}
                  <p className="truncate text-sm text-white/64">{user.email}</p>
                  <p className="mt-1 truncate text-xs text-white/40">
                    /{user.slug} · joined {day(user.createdAt) ?? 'at an unknown date'}
                  </p>
                </div>

                <div className="flex flex-wrap items-center gap-2">
                  {/* Never colour alone: each state is a word as well as a tone. */}
                  <Tag variant={user.emailVerified ? 'success' : 'default'}>
                    {user.emailVerified ? 'Email verified' : 'Email unverified'}
                  </Tag>
                  {user.suspended && <Tag variant="danger">Suspended</Tag>}
                  {user.deletionScheduledAt !== null && <Tag variant="warning">Leaving</Tag>}
                </div>
              </div>

              {user.suspended && (
                <p className="mt-3 text-sm text-white/64">
                  Suspended {day(user.suspendedAt) ?? 'at an unknown date'}
                  {user.suspensionReason !== null && `: ${user.suspensionReason}`}
                </p>
              )}

              <div className="mt-4 flex flex-wrap gap-2">
                {user.suspended ? (
                  <Pill
                    variant="ghost"
                    size="sm"
                    disabled={busyIds.has(user.id)}
                    onClick={() => void commitReinstatement(user)}
                  >
                    {busyIds.has(user.id) ? 'Reinstating' : 'Reinstate'}
                  </Pill>
                ) : (
                  <Pill
                    variant="danger"
                    size="sm"
                    disabled={busyIds.has(user.id)}
                    onClick={() => {
                      setDialogError(null);
                      setReason('');
                      setPending(user);
                    }}
                  >
                    Suspend
                  </Pill>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {status === 'ready' && loaded.length > 0 && (
        <p className="mt-3 text-sm text-white/40">
          Showing {accounts(loaded.length)}
          {cursor !== null && ', and there are more.'}
        </p>
      )}

      {status === 'ready' && cursor !== null && (
        <Pill
          variant="ghost"
          size="sm"
          className="mt-3"
          disabled={loadingMore}
          onClick={() => void loadMore()}
        >
          {loadingMore ? 'Loading' : 'Load more'}
        </Pill>
      )}

      {status === 'failed' && (
        <Pill variant="ghost" size="sm" className="mt-4" onClick={() => setAttempt((n) => n + 1)}>
          Try again
        </Pill>
      )}

      {pending !== null && (
        <Modal
          open
          onOpenChange={(next) => {
            if (!next && !dialogBusy) setPending(null);
          }}
          size="md"
          title={`Suspend ${pending.name}?`}
          description={`${pending.email} will be signed out of every device and refused at sign-in until somebody reinstates them.`}
          closeOnBackdropClick={false}
          showClose={false}
          footer={
            <>
              <Pill variant="ghost" disabled={dialogBusy} onClick={() => setPending(null)}>
                Cancel
              </Pill>
              <Pill variant="danger" disabled={dialogBusy} onClick={() => void commitBan()}>
                {dialogBusy ? 'Suspending' : 'Suspend account'}
              </Pill>
            </>
          }
        >
          <p className="text-sm text-on-white/72">
            Their campaigns and pledges are left exactly where they are. This is reversible, unlike
            suspending a campaign — but every session they hold is revoked now and is not restored
            by reinstating them.
          </p>

          <div className="mt-4">
            <Field
              label="Reason"
              required
              hint="The person is told this, and an appeal is answered from it."
              error={dialogError}
            >
              {/* No `maxLength`: a hard cap silently truncates a pasted reason and
                  takes the counter's one useful message away. */}
              <Textarea
                rows={4}
                value={reason}
                disabled={dialogBusy}
                onChange={(event) => setReason(event.target.value)}
              />
              <CharacterCount count={Array.from(reason).length} limit={REASON_MAX_CHARACTERS} />
            </Field>
          </div>
        </Modal>
      )}
    </section>
  );
}
