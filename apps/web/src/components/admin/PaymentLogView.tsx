'use client';

import { useCallback, useEffect, useState } from 'react';
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
import {
  PAYMENT_PAGE_SIZE,
  TRANSACTION_STATUS_LABELS,
  TRANSACTION_TYPE_LABELS,
  readPaymentLog,
  statusVariant,
  type LoggedTransaction,
} from '../../lib/admin/payments';
import {
  consoleMessageFor,
  shortId,
  statusFor,
  wasAborted,
  type ConsoleStatus,
} from '../../lib/admin/refusals';
import { formatMoney } from '../../lib/money';
import { ConsoleRefusal } from './ConsoleRefusal';

const SUBJECT = 'the payment log';

/** Which of the two identifiers the search box is holding. */
type Scope = 'pledge' | 'project';

/**
 * §4.11's AD-05: every charge, its provider reference, and its state history — issue #304.
 *
 * <h2>The state history is the list, not a column on a row</h2>
 *
 * §7.2 makes `transactions` append-only, so a charge that was pending and later succeeded is
 * two rows sharing an idempotency key rather than one row that changed. This screen therefore
 * shows attempts and not payments: four rows for one pledge is a card that was refused three
 * times and collected on the fourth, and that sequence is exactly what §9.6's retry schedule
 * is argued from. Collapsing them into one line per pledge would delete the only thing here
 * that answers "why was this person charged on a Thursday".
 *
 * <h2>The search is a form, not a keystroke handler</h2>
 *
 * Every read of this log is audited — it hands somebody's payment history to an account with
 * no relationship to it — so a request per character would write a row per keystroke into the
 * one table with no retention rule. `UserDirectory` made the same decision for the same
 * reason.
 *
 * <h2>Money is formatted, never computed</h2>
 *
 * The amount arrives as a string because §10.3 forbids a JSON number for money, and nothing
 * on this screen adds two of them up. `lib/money.ts` formats against the currency on the row,
 * which is the campaign's and never the reader's.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 puts motion at its lowest as money gets closer, and this is the
 * screen that is entirely money. 150ms of colour on a control, and nothing else.
 */
export function PaymentLogView() {
  const [status, setStatus] = useState<ConsoleStatus>('loading');
  const [scope, setScope] = useState<Scope>('project');
  const [term, setTerm] = useState('');
  const [submitted, setSubmitted] = useState<{ scope: Scope; id: string } | null>(null);
  const [rows, setRows] = useState<readonly LoggedTransaction[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  const filter = submitted;

  useEffect(() => {
    const controller = new AbortController();

    async function load(): Promise<void> {
      setStatus('loading');
      try {
        const page = await readPaymentLog({
          pledgeId: filter?.scope === 'pledge' ? filter.id : null,
          projectId: filter?.scope === 'project' ? filter.id : null,
          limit: PAYMENT_PAGE_SIZE,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;

        setRows(page.transactions);
        setCursor(page.nextCursor ?? null);
        setError(null);
        setStatus('ready');
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;

        const next = statusFor(cause);
        if (next === 'failed') setError(consoleMessageFor(cause, SUBJECT));
        setStatus(next);
      }
    }

    void load();
    return () => controller.abort();
  }, [filter, attempt]);

  const loadMore = useCallback(async (): Promise<void> => {
    if (cursor === null || loadingMore) return;

    setLoadingMore(true);
    setError(null);
    try {
      const page = await readPaymentLog({
        pledgeId: filter?.scope === 'pledge' ? filter.id : null,
        projectId: filter?.scope === 'project' ? filter.id : null,
        after: cursor,
        limit: PAYMENT_PAGE_SIZE,
      });
      setRows((previous) => [...previous, ...page.transactions]);
      setCursor(page.nextCursor ?? null);
    } catch (cause) {
      setError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setLoadingMore(false);
    }
  }, [cursor, filter, loadingMore]);

  if (status === 'signed-out' || status === 'forbidden') {
    return <ConsoleRefusal status={status} subject={SUBJECT} />;
  }

  function submit(event: React.FormEvent): void {
    event.preventDefault();
    const trimmed = term.trim();
    setSubmitted(trimmed === '' ? null : { scope, id: trimmed });
  }

  return (
    <section aria-labelledby="payment-log-heading">
      <h2 id="payment-log-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
        Provider calls
        {status === 'ready' && (
          <span className="ml-2 text-xs font-normal text-white/40">{rows.length}</span>
        )}
      </h2>

      <form onSubmit={submit} className="mt-4 flex flex-wrap items-end gap-3">
        <Field
          label="Identifier"
          hint="A campaign or a pledge. The whole identifier, not part of one."
          className="min-w-[280px] flex-1"
        >
          <TextInput
            type="search"
            value={term}
            onChange={(event) => setTerm(event.target.value)}
            placeholder="00000000-0000-0000-0000-000000000000"
          />
        </Field>

        {/*
          Two radio buttons rather than a guess. Both identifiers are UUIDs and nothing about
          the string says which one it is, so a screen that inferred would sometimes ask the
          service for a campaign that is a pledge and answer with an empty page — which on
          this surface reads as "nothing was ever charged".
        */}
        <fieldset className="flex items-end gap-3">
          <legend className="sr-only">What the identifier names</legend>
          {(['project', 'pledge'] as const).map((option) => (
            <label key={option} className="flex items-center gap-2 pb-2.5 text-sm text-white/64">
              <input
                type="radio"
                name="payment-scope"
                value={option}
                checked={scope === option}
                onChange={() => setScope(option)}
                className="accent-[var(--lime-500)]"
              />
              {option === 'project' ? 'Campaign' : 'Pledge'}
            </label>
          ))}
        </fieldset>

        <Pill type="submit" variant="outline" size="sm" className="mb-1">
          Search
        </Pill>
        {submitted !== null && (
          <Pill
            variant="ghost"
            size="sm"
            className="mb-1"
            onClick={() => {
              setTerm('');
              setSubmitted(null);
            }}
          >
            Clear
          </Pill>
        )}
      </form>

      {error && (
        <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
          {error}
        </InlineAlert>
      )}

      {status === 'loading' && (
        <SkeletonGroup label="Loading the payment log" className="mt-4">
          <div className="space-y-3">
            {[0, 1, 2].map((row) => (
              <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                <Skeleton height="1rem" width="40%" />
                <Skeleton height="0.875rem" width="65%" className="mt-3" />
              </div>
            ))}
          </div>
        </SkeletonGroup>
      )}

      {status === 'ready' && rows.length === 0 && (
        <EmptyState
          className="mt-4"
          variant={submitted === null ? 'empty' : 'filtered'}
          title={submitted === null ? 'Nothing has been charged yet' : 'No calls for that identifier'}
          description={
            submitted === null
              ? 'Every call to a payment provider is recorded here, including the ones that were refused. An empty log means no collection has run on this deployment.'
              : 'Nothing has been asked of a provider about that campaign or pledge. Check the identifier, and which of the two it is.'
          }
        />
      )}

      {status === 'ready' && rows.length > 0 && (
        <ul className="mt-4 flex list-none flex-col gap-2">
          {rows.map((row) => (
            <TransactionRow key={row.id} transaction={row} />
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
          {loadingMore ? 'Loading' : 'Load more'}
        </Pill>
      )}

      {status === 'failed' && (
        <Pill variant="ghost" size="sm" className="mt-4" onClick={() => setAttempt((n) => n + 1)}>
          Try again
        </Pill>
      )}
    </section>
  );
}

/**
 * One call to a provider.
 *
 * <p><strong>The provider reference is printed in full, not shortened.</strong> Every other
 * identifier on the console's screens is cut to eight characters because nobody reads them —
 * this one is the string a support conversation and a dispute are both conducted in, so it is
 * the one thing on the row somebody copies. It wraps rather than truncates for the same
 * reason: half a reference is worse than a long one.
 *
 * <p>The decline is shown in the provider's own words as well as its code. The code is what a
 * client branches on and the sentence is what a person reads, and the two frequently disagree
 * in useful ways.
 */
function TransactionRow({ transaction }: { readonly transaction: LoggedTransaction }) {
  return (
    <li className="rounded-lg border border-white/8 bg-surface-1 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[15px] font-medium text-white">
            {formatMoney(transaction.amount)}{' '}
            <span className="font-normal text-white/48">
              {TRANSACTION_TYPE_LABELS[transaction.type]}
            </span>
          </p>
          <p className="mt-1 text-sm text-white/64">
            campaign{' '}
            <span className="font-mono" title={transaction.projectId}>
              {shortId(transaction.projectId)}
            </span>
            {transaction.pledgeId != null && (
              <>
                {' · pledge '}
                <span className="font-mono" title={transaction.pledgeId}>
                  {shortId(transaction.pledgeId)}
                </span>
              </>
            )}
            {' · attempt '}
            {transaction.attemptNumber}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Tag>{transaction.provider}</Tag>
          <Tag variant={statusVariant(transaction.status)}>
            {TRANSACTION_STATUS_LABELS[transaction.status]}
          </Tag>
          <time
            dateTime={transaction.createdAt}
            className="text-xs text-white/40"
            title={transaction.createdAt}
          >
            {new Date(transaction.createdAt).toLocaleString()}
          </time>
        </div>
      </div>

      {transaction.providerTransactionId != null && transaction.providerTransactionId !== '' ? (
        <p className="mt-2 break-all font-mono text-xs text-white/40">
          {transaction.providerTransactionId}
        </p>
      ) : (
        /*
          A call with no reference is not a missing field. It is a request that never got an
          answer — a timeout — and it is on the row precisely because it may have charged
          somebody without the platform knowing.
        */
        <p className="mt-2 text-xs text-white/32">
          The provider gave no reference. This attempt may have reached it.
        </p>
      )}

      {transaction.failureCode != null && (
        <p className="mt-2 text-sm text-white/64">
          <span className="font-mono text-white/48">{transaction.failureCode}</span>
          {transaction.failureMessage != null && <> — {transaction.failureMessage}</>}
        </p>
      )}
    </li>
  );
}
