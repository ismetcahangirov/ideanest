'use client';

import { useCallback, useEffect, useState } from 'react';
import {
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
} from '@ideanest/ui';
import {
  LEDGER_ACCOUNTS,
  LEDGER_PAGE_SIZE,
  accountLabel,
  readLedger,
  type LedgerBalance,
  type LedgerPosting,
} from '../../lib/admin/ledger';
import {
  consoleMessageFor,
  requiredCapabilityFrom,
  shortId,
  statusFor,
  wasAborted,
  type ConsoleStatus,
} from '../../lib/admin/refusals';
import { formatMoney } from '../../lib/money';
import { fillNodes } from '../../lib/i18n/placeholders';
import { pluralise } from '../../lib/i18n/plurals';
import { useRouteLocale } from '../../lib/i18n/useRouteLocale';
import { formatExactTime } from '../../lib/time';
import type { Locale } from '../../lib/i18n/locale';
import type { LedgerExplorerCopy } from '../../lib/i18n/admin/money-copy';
import { creatorIdsIn } from '../../lib/admin/ledger';
import type { DirectoryNames } from '../../lib/admin/directory';
import { ConsoleRefusal } from './ConsoleRefusal';
import { EntityName } from './ConsoleIdentity';
import { useDirectoryNames } from './useDirectoryNames';

/**
 * §4.11's AD-05: the double-entry ledger, readable by account and by campaign — issue #305.
 *
 * <h2>The unit on this screen is a posting, and that is the whole design</h2>
 *
 * §7.2's invariant is stated per transaction — for every `transaction_id`, the debits equal
 * the credits — so a screen that listed entries would be one on which the platform's central
 * accounting rule is invisible. Every card here is one posting with all of its sides, and it
 * says whether the two halves agree. The service enforces that they do; the screen shows it,
 * because "the database checks" is not a thing anybody can see.
 *
 * <h2>Filtering by account never hides half a posting</h2>
 *
 * Narrowing to escrow selects which postings appear. It does not select which lines of one
 * appear — a ledger showing the escrow side of a double entry would be showing a balance that
 * does not balance, which is exactly the state this table exists to make unreachable. The
 * service does the same thing one layer down: it pages over postings and then loads every
 * entry of the ones it named.
 *
 * <h2>The balances are at the top and are not narrowed by the account filter</h2>
 *
 * Twenty postings out of however many say nothing about whether escrow holds what it should;
 * the totals do. And they stay whole while the list narrows, because filtering to escrow does
 * not make the other accounts stop existing — a one-line balance panel would read as though
 * it were the whole ledger.
 *
 * <h2>Nothing here can change anything</h2>
 *
 * There is no action on a posting, because there is no endpoint that could take one. V41 puts
 * a trigger on `ledger_entries` that raises on UPDATE and DELETE, and §7.2 says corrections
 * are new rows. A ledger that can be edited is a ledger; one that cannot is evidence.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5's lowest budget, for the screen §22.1 treats as a regulatory
 * record.
 */
export interface LedgerExplorerProps {
  readonly copy: LedgerExplorerCopy;
}

export function LedgerExplorer({ copy }: LedgerExplorerProps) {
  const locale = useRouteLocale();
  const [status, setStatus] = useState<ConsoleStatus>('loading');
  // #400: which of the two 403s this is. Only read while `status` is `forbidden`.
  const [capability, setCapability] = useState<string | null>(null);
  const [account, setAccount] = useState<string | null>(null);
  const [term, setTerm] = useState('');
  const [projectId, setProjectId] = useState<string | null>(null);
  const [postings, setPostings] = useState<readonly LedgerPosting[]>([]);
  const [balances, setBalances] = useState<readonly LedgerBalance[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  /*
   * The campaigns each posting is about, and the creators whose accounts it moved — #402.
   * A creator's ledger account is stored as `creator:{uuid}`, so the identifiers come out
   * of the account names rather than out of a field of their own.
   */
  const names = useDirectoryNames(
    creatorIdsIn([
      ...balances.map((balance) => balance.account),
      ...postings.flatMap((posting) => posting.lines.map((line) => line.account)),
    ]),
    postings.map((posting) => posting.projectId),
  );

  useEffect(() => {
    const controller = new AbortController();

    async function load(): Promise<void> {
      setStatus('loading');
      try {
        const view = await readLedger({
          account,
          projectId,
          limit: LEDGER_PAGE_SIZE,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;

        setPostings(view.postings);
        setBalances(view.balances);
        setCursor(view.nextCursor ?? null);
        setError(null);
        setStatus('ready');
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;

        const next = statusFor(cause);
        setCapability(requiredCapabilityFrom(cause));
        if (next === 'failed') setError(consoleMessageFor(cause, copy.subject, copy.refusals));
        setStatus(next);
      }
    }

    void load();
    return () => controller.abort();
    // The copy is one object per server render — see `useConsoleResource` for the argument.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account, projectId, attempt]);

  const loadMore = useCallback(async (): Promise<void> => {
    if (cursor === null || loadingMore) return;

    setLoadingMore(true);
    setError(null);
    try {
      const view = await readLedger({ account, projectId, after: cursor, limit: LEDGER_PAGE_SIZE });
      setPostings((previous) => [...previous, ...view.postings]);
      setCursor(view.nextCursor ?? null);
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setLoadingMore(false);
    }
  }, [account, cursor, loadingMore, projectId, copy]);

  if (status === 'signed-out' || status === 'forbidden') {
    return <ConsoleRefusal status={status} capability={capability} subject={copy.subject} copy={copy.refusals} />;
  }

  function submit(event: React.FormEvent): void {
    event.preventDefault();
    const trimmed = term.trim();
    setProjectId(trimmed === '' ? null : trimmed);
  }

  const unbalanced = postings.filter((posting) => !posting.balanced);

  return (
    <section aria-labelledby="ledger-heading">
      <h2 id="ledger-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
        {copy.heading}
        {status === 'ready' && (
          <span className="ml-2 text-xs font-normal text-white/40">{postings.length}</span>
        )}
      </h2>

      {/*
        THE ONE ALERT ON THIS SCREEN THAT SHOULD NEVER FIRE. V41's deferred constraint trigger
        refuses to commit a posting whose sides disagree, so reaching this means a row arrived
        past both the application and the database — which is an incident, and the person
        looking at the ledger is the one who needs to know first.
      */}
      {status === 'ready' && unbalanced.length > 0 && (
        <InlineAlert variant="danger" title={copy.unbalancedTitle} className="mt-4">
          {pluralise(locale, copy.unbalanced, unbalanced.length)}
        </InlineAlert>
      )}

      {status === 'ready' && balances.length > 0 && (
        <BalancePanel balances={balances} names={names} scoped={projectId !== null} copy={copy} />
      )}

      <div className="mt-6 space-y-3">
        <ChipRow aria-label={copy.accountFilter}>
          <Chip active={account === null} onClick={() => setAccount(null)}>
            {copy.everyAccount}
          </Chip>
          {LEDGER_ACCOUNTS.map((value) => (
            <Chip key={value} active={account === value} onClick={() => setAccount(value)}>
              {copy.money.account[value] ?? value}
            </Chip>
          ))}
        </ChipRow>

        <form onSubmit={submit} className="flex flex-wrap items-end gap-3">
          <Field
            label={copy.campaignLabel}
            hint={copy.campaignHint}
            className="min-w-[280px] flex-1"
          >
            <TextInput
              type="search"
              value={term}
              onChange={(event) => setTerm(event.target.value)}
              placeholder="00000000-0000-0000-0000-000000000000"
            />
          </Field>
          <Pill type="submit" variant="outline" size="sm" className="mb-1">
            {copy.apply}
          </Pill>
          {projectId !== null && (
            <Pill
              variant="ghost"
              size="sm"
              className="mb-1"
              onClick={() => {
                setTerm('');
                setProjectId(null);
              }}
            >
              {copy.clear}
            </Pill>
          )}
        </form>
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
              <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                <Skeleton height="1rem" width="35%" />
                <Skeleton height="0.875rem" width="70%" className="mt-3" />
                <Skeleton height="0.875rem" width="70%" className="mt-2" />
              </div>
            ))}
          </div>
        </SkeletonGroup>
      )}

      {status === 'ready' && postings.length === 0 && (
        <EmptyState
          className="mt-4"
          variant={account === null && projectId === null ? 'empty' : 'filtered'}
          title={account === null && projectId === null ? copy.emptyTitle : copy.filteredTitle}
          description={
            account === null && projectId === null ? copy.emptyBody : copy.filteredBody
          }
        />
      )}

      {status === 'ready' && postings.length > 0 && (
        <ul className="mt-4 flex list-none flex-col gap-2">
          {postings.map((posting) => (
            <PostingCard
              key={posting.transactionId}
              posting={posting}
              locale={locale}
              names={names}
              copy={copy}
            />
          ))}
        </ul>
      )}

      {status === 'ready' && cursor !== null && (
        <Pill
          variant="ghost"
          size="sm"
          className="mt-4"
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
    </section>
  );
}

/**
 * What each account holds.
 *
 * <p>Says which question it is answering — the platform's or one campaign's — because the two
 * numbers differ by orders of magnitude and a panel that did not say would be read as
 * whichever the reader expected.
 *
 * <p>A negative balance is drawn in the same colour as a positive one. In double-entry a
 * credit balance is not a problem: `platform_fee` is negative by construction, because fees
 * owed to the platform are credits. Colouring the sign would mark five of six accounts as
 * wrong on every render.
 */
function BalancePanel({
  balances,
  names,
  scoped,
  copy,
}: {
  readonly balances: readonly LedgerBalance[];
  readonly names: DirectoryNames;
  readonly scoped: boolean;
  readonly copy: LedgerExplorerCopy;
}) {
  return (
    <div className="mt-4 rounded-xl border border-white/8 bg-surface-1 p-4 sm:p-5">
      <h3 className="text-sm font-medium text-white">
        {scoped ? copy.balancesScoped : copy.balancesPlatform}
      </h3>
      <dl className="mt-3 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
        {balances.map((balance) => (
          <div key={`${balance.account}-${balance.net.currency}`} className="flex justify-between gap-4">
            <dt className="text-white/48" title={balance.account}>
              {accountLabel(balance.account, copy.money, names)}
            </dt>
            <dd className="font-mono tabular-nums text-white">{formatMoney(balance.net)}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-3 text-xs text-white/32">{copy.balancesNote}</p>
    </div>
  );
}

/**
 * One posting: every entry the ledger wrote about a single provider call.
 *
 * <p>The transaction identifier is the join to the payment log (#304) — what was asked of a
 * provider, and here what it meant — so it is printed rather than shortened away entirely,
 * and the full value is on the `title` for copying.
 */
function PostingCard({
  posting,
  locale,
  names,
  copy,
}: {
  readonly posting: LedgerPosting;
  readonly locale: Locale;
  readonly names: DirectoryNames;
  readonly copy: LedgerExplorerCopy;
}) {
  return (
    <li className="rounded-lg border border-white/8 bg-surface-1 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <p className="text-sm text-white/64">
          {fillNodes(copy.postingLine, {
            transaction: (
              <span className="font-mono text-white" title={posting.transactionId}>
                {shortId(posting.transactionId)}
              </span>
            ),
            campaign: (
              <EntityName
                id={posting.projectId}
                names={names}
                kind="project"
                copy={copy.identity}
              />
            ),
          })}
        </p>
        <div className="flex items-center gap-2">
          {!posting.balanced && <Tag variant="danger">{copy.doesNotBalance}</Tag>}
          <time
            dateTime={posting.createdAt}
            className="text-xs text-white/40"
            title={posting.createdAt}
          >
            {formatExactTime(posting.createdAt, locale)}
          </time>
        </div>
      </div>

      {/*
        Both sides, always. The account filter above decides which postings are drawn and
        never which lines of one — see the component's docblock.
      */}
      <ul className="mt-3 flex list-none flex-col gap-1">
        {posting.lines.map((line, index) => (
          <li
            key={`${line.account}-${line.direction}-${index}`}
            className="flex items-baseline justify-between gap-4 text-sm"
          >
            {/*
              #402: a creator's ledger account is stored as `creator:{uuid}` and used to
              render as "Creator 07afbabf" — the one account on the ledger that belongs to
              a person, and the only one that could not be read as a person.
            */}
            <span className="text-white/64" title={line.account}>
              {accountLabel(line.account, copy.money, names)}
            </span>
            <span className="flex items-baseline gap-3">
              <span className="text-xs uppercase tracking-wide text-white/40">
                {line.direction === 'DEBIT' ? copy.debit : copy.credit}
              </span>
              <span className="font-mono tabular-nums text-white">{formatMoney(line.amount)}</span>
            </span>
          </li>
        ))}
      </ul>
    </li>
  );
}
