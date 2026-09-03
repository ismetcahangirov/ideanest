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
import { Link } from '../../i18n/navigation';
import { ApiError } from '../../lib/api/problem';
import {
  REASON_MAX_CHARACTERS,
  banUser,
  listUsers,
  reinstateUser,
  type AdminUser,
} from '../../lib/admin/api';
import { fillPlaceholders } from '../../lib/i18n/placeholders';
import { pluralise } from '../../lib/i18n/plurals';
import { useRouteLocale } from '../../lib/i18n/useRouteLocale';
import { formatDate } from '../../lib/time';
import type { Locale } from '../../lib/i18n/locale';
import type { UserDirectoryCopy } from '../../lib/i18n/admin/people-copy';
import { requiredCapabilityFrom } from '../../lib/admin/refusals';
import { ConsoleCount } from './ConsoleCount';
import { ConsoleRefusal } from './ConsoleRefusal';
import { CopyIdentifier } from './ConsoleIdentity';

type Status = 'loading' | 'ready' | 'failed' | 'signed-out' | 'forbidden';

/**
 * Turns a refusal into something a moderator can act on.
 *
 * Branches on `problem.code` and never on prose, like the moderation queue: two
 * refusals that cannot be told apart would force this screen to match on
 * sentences the service is free to reword.
 */
function messageFor(cause: unknown, copy: UserDirectoryCopy): string {
  if (cause instanceof ApiError) {
    if (cause.status === 403) return copy.notStaff;

    const code = cause.problem?.code;

    if (code === 'ACCOUNT_NOT_FOUND') return copy.accountNotFound;
    if (code === 'ACCOUNT_SUSPENSION_REFUSED') return copy.selfSuspend;

    return cause.problem?.detail ?? cause.problem?.title ?? copy.refusals.refused;
  }
  return copy.refusals.unreachable;
}

function wasAborted(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}

/**
 * The day, in the reader's language. An instant to the second says more than anybody needs.
 *
 * <p>`toLocaleDateString()` with no argument until #401, which is the *browser's* language
 * rather than the route's — so an Azerbaijani console on an American laptop rendered
 * `7/27/2026` under a heading in Azerbaijani.
 */
function day(instant: string | null, locale: Locale): string | null {
  return instant === null ? null : formatDate(instant, locale);
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
export interface UserDirectoryProps {
  readonly copy: UserDirectoryCopy;
}

export function UserDirectory({ copy }: UserDirectoryProps) {
  const locale = useRouteLocale();
  const [status, setStatus] = useState<Status>('loading');
  // #400: which of the two 403s this is. Only read while `status` is `forbidden`.
  const [capability, setCapability] = useState<string | null>(null);
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
          setCapability(requiredCapabilityFrom(cause));
          setStatus('forbidden');
          return;
        }
        setError(messageFor(cause, copy));
        setStatus('failed');
      }
    }

    void load();
    return () => controller.abort();
    // The copy is one object per server render — see `useConsoleResource` for the argument.
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      setError(messageFor(cause, copy));
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, loadingMore, submitted, suspendedOnly, copy]);

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
      setDialogError(copy.reasonRequired);
      return;
    }
    if (Array.from(reason).length > REASON_MAX_CHARACTERS) {
      setDialogError(
        fillPlaceholders(copy.reasonTooLong, { limit: String(REASON_MAX_CHARACTERS) }),
      );
      return;
    }

    setDialogBusy(true);
    setDialogError(null);
    setError(null);
    markBusy(pending.id, true);

    try {
      const updated = await banUser(pending.id, trimmed);
      applyUser(updated);
      setNotice(fillPlaceholders(copy.suspendedNotice, { name: updated.name }));
      setPending(null);
      setReason('');
      // The button that opened the dialog may have gone with its row. Moving
      // focus to the heading puts a keyboard user somewhere that still exists.
      headingRef.current?.focus();
    } catch (cause) {
      // Everything keeps the dialog open with the reason still in it: nothing
      // changed, and retyping it is the last thing a refused moderator should do.
      setDialogError(messageFor(cause, copy));
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
      setNotice(fillPlaceholders(copy.reinstatedNotice, { name: updated.name }));
    } catch (cause) {
      setError(messageFor(cause, copy));
    } finally {
      markBusy(user.id, false);
    }
  }

  if (status === 'signed-out') {
    return (
      <InlineAlert variant="info" title={copy.signedOutTitle}>
        {copy.signedOutBody}
      </InlineAlert>
    );
  }

  if (status === 'forbidden') {
    // Two 403s, and only the first is this screen's — #400. A colleague short of a
    // capability gets the console's sentence, which names the one the service asked for;
    // the screen's own words are for somebody who does not work here at all.
    if (capability != null && capability !== '') {
      return (
        <ConsoleRefusal
          status={status}
          capability={capability}
          subject={copy.subject}
          copy={copy.refusals}
        />
      );
    }

    return (
      <InlineAlert variant="info" title={copy.forbiddenTitle}>
        {copy.forbiddenBody}
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
          {copy.heading}
          {status === 'ready' && (
            <ConsoleCount loaded={loaded.length} more={cursor !== null} copy={copy.count} />
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
          label={copy.searchLabel}
          hint={copy.searchHint}
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
          {copy.search}
        </Pill>
      </form>

      <ChipRow aria-label={copy.standing} className="mt-3">
        <Chip active={suspendedOnly} onClick={() => setSuspendedOnly((previous) => !previous)}>
          {copy.suspendedOnly}
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
        <InlineAlert variant="danger" title={copy.errorTitle} className="mt-4">
          {error}
        </InlineAlert>
      )}

      {status === 'loading' && (
        <SkeletonGroup label={copy.loadingList} className="mt-4">
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
          title={submitted !== '' || suspendedOnly ? copy.filteredTitle : copy.emptyTitle}
          description={
            submitted !== '' || suspendedOnly ? copy.filteredBody : copy.emptyBody
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
                  {/*
                    #404: the row's only control was "suspend", and the copy under it says
                    that suspending changes nothing about the campaigns somebody created or
                    the pledges they made — context that was reachable from nowhere in the
                    console. The name is the way in now.

                    The name and not a separate "view" control, because the row already has
                    one destructive button and a second control beside it is a second thing
                    to aim at; a person's name leading to that person is the ordinary
                    arrangement, and the heading of the page it opens is the same name.
                  */}
                  <Link
                    href={`/admin/users/${encodeURIComponent(user.id)}`}
                    className="block truncate rounded-lg text-sm font-medium text-white underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                  >
                    {user.name}
                  </Link>
                  {/* The address is why this screen exists and why every read of it
                      is audited. Nothing else on the platform shows one. */}
                  <p className="truncate text-sm text-white/64">{user.email}</p>
                  <p className="mt-1 flex flex-wrap items-baseline gap-x-2 text-xs text-white/40">
                    <span className="truncate">
                      /{user.slug} ·{' '}
                      {fillPlaceholders(copy.joined, {
                        date: day(user.createdAt, locale) ?? copy.unknownDate,
                      })}
                    </span>
                    {/*
                      #402: this screen is what `/admin/staff` calls "the account directory"
                      and what its help text tells an operator to get a full identifier
                      from. It displayed none — not in the row's markup, not anywhere in the
                      page's text — so the identifier the form required was not obtainable
                      from the screen the form named, and granting a role could not be
                      finished inside the console. The fragment is here, the whole value is
                      on the control, and `AccountPicker` removes the need for either.
                    */}
                    <span className="font-mono text-white/32" title={user.id}>
                      {user.id.slice(0, 8)}
                    </span>
                    <CopyIdentifier id={user.id} copy={copy.identity} />
                  </p>
                </div>

                <div className="flex flex-wrap items-center gap-2">
                  {/* Never colour alone: each state is a word as well as a tone. */}
                  <Tag variant={user.emailVerified ? 'success' : 'default'}>
                    {user.emailVerified ? copy.emailVerified : copy.emailUnverified}
                  </Tag>
                  {user.suspended && <Tag variant="danger">{copy.suspendedTag}</Tag>}
                  {user.deletionScheduledAt !== null && (
                    <Tag variant="warning">{copy.leaving}</Tag>
                  )}
                </div>
              </div>

              {user.suspended && (
                <p className="mt-3 text-sm text-white/64">
                  {fillPlaceholders(copy.suspendedOn, {
                    date: day(user.suspendedAt, locale) ?? copy.unknownDate,
                  })}
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
                    {busyIds.has(user.id) ? copy.reinstating : copy.reinstate}
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
                    {copy.suspend}
                  </Pill>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {status === 'ready' && loaded.length > 0 && (
        <p className="mt-3 text-sm text-white/40">
          {fillPlaceholders(copy.showing, {
            accounts: pluralise(locale, copy.accountCount, loaded.length),
          })}
          {cursor !== null && copy.andMore}
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
          {loadingMore ? copy.loading : copy.loadMore}
        </Pill>
      )}

      {status === 'failed' && (
        <Pill variant="ghost" size="sm" className="mt-4" onClick={() => setAttempt((n) => n + 1)}>
          {copy.tryAgain}
        </Pill>
      )}

      {pending !== null && (
        <Modal
          open
          onOpenChange={(next) => {
            if (!next && !dialogBusy) setPending(null);
          }}
          size="md"
          title={fillPlaceholders(copy.suspendTitle, { name: pending.name })}
          description={fillPlaceholders(copy.suspendDescription, { email: pending.email })}
          closeOnBackdropClick={false}
          showClose={false}
          footer={
            <>
              <Pill variant="ghost" disabled={dialogBusy} onClick={() => setPending(null)}>
                {copy.cancel}
              </Pill>
              <Pill variant="danger" disabled={dialogBusy} onClick={() => void commitBan()}>
                {dialogBusy ? copy.suspending : copy.suspendAccount}
              </Pill>
            </>
          }
        >
          <p className="text-sm text-on-white/72">{copy.suspendBody}</p>

          <div className="mt-4">
            <Field
              label={copy.reasonLabel}
              required
              hint={copy.reasonHint}
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
