'use client';

import { useEffect, useState } from 'react';
import { InlineAlert, Skeleton, SkeletonGroup, StatBlock, StatRow, Tag } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import { getFinance, type CampaignFinance, type FinancePayout } from '../../lib/dashboard/finance';
import { useRouteLocale } from '../../lib/i18n/useRouteLocale';
import { formatMoney } from '../../lib/money';
import { formatExactTime } from '../../lib/time';

/**
 * §4.7's CD-16: gross, fees, tax, refunds and net — issue #99.
 *
 * <h2>THE WHOLE BREAKDOWN, NEVER THE NET ALONE</h2>
 *
 * A creator looking at this screen is asking "why was I paid this", and that is five questions
 * rather than one. A single figure with a note saying "fees deducted" is not something anybody
 * can check, so every deduction is a row and the rows add up in front of the reader — the same
 * argument `PayoutResponses` makes for the screen where staff sign the money out.
 *
 * <h2>Projected and settled are labelled, loudly</h2>
 *
 * Before a payout has been calculated, the fees are what §5.2's schedule *would* charge. After
 * one, they are what it *was* priced at. Those are different statements about somebody's money
 * and this panel never renders them identically: the badge says which, and the projected
 * version says in a sentence that the figures can still move.
 *
 * <h2>Money is formatted, never computed</h2>
 *
 * Every figure on this panel arrives from the service as a decimal string and is printed. No
 * total is added up here — not even to check the others — because CLAUDE.md §3 puts money
 * arithmetic behind `decimal.js` and a panel that quietly re-derived a net would be a second
 * answer to the question the service already answered.
 *
 * <h2>No entry animation</h2>
 *
 * `docs/motion-system.md` §5 puts the smallest budget on the surfaces closest to money, and
 * this is the closest one there is. `DashboardOverview` makes the same call for the same
 * reason.
 */

type Status = 'loading' | 'ready' | 'failed';

/** Turns a refusal into something a creator can act on. */
function messageFor(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 401) {
      return 'Your session has expired. Sign in again to see this campaign.';
    }
    if (cause.status === 403) {
      return 'Your collaborator grant on this campaign does not include the finances, so these figures are not yours to see.';
    }
    if (cause.status === 404) {
      return 'That campaign does not exist, or it is not one you work on.';
    }
  }
  return 'The financial summary could not be loaded. It is the service rather than your campaign — try again shortly.';
}

/** §9.5's payout states, as words. A state this list has not met renders as its own name. */
const PAYOUT_STATES: Readonly<Record<string, string>> = {
  CALCULATED: 'Calculated',
  PENDING_APPROVAL: 'Waiting for approval',
  APPROVED: 'Approved',
  PAID: 'Paid',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

export interface FinancialSummaryProps {
  readonly projectId: string;
  /** Injected by tests. Defaults to the real reader. */
  readonly load?: (projectId: string) => Promise<CampaignFinance>;
}

export function FinancialSummary({ projectId, load }: FinancialSummaryProps) {
  const locale = useRouteLocale();
  const [status, setStatus] = useState<Status>('loading');
  const [finance, setFinance] = useState<CampaignFinance | null>(null);
  const [failure, setFailure] = useState('');

  useEffect(() => {
    let cancelled = false;
    const reader = load ?? getFinance;

    reader(projectId)
      .then((body) => {
        if (cancelled) return;
        setFinance(body);
        setStatus('ready');
      })
      .catch((cause: unknown) => {
        if (cancelled) return;
        setFailure(messageFor(cause));
        setStatus('failed');
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  if (status === 'loading') {
    return (
      <SkeletonGroup label="Loading the campaign's finances">
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </SkeletonGroup>
    );
  }

  if (status === 'failed' || finance === null) {
    return <InlineAlert variant="danger">{failure}</InlineAlert>;
  }

  const projected = finance.basis === 'PROJECTED';

  return (
    <section aria-labelledby="finance-heading">
      <div className="flex flex-wrap items-center gap-3">
        <h1
          id="finance-heading"
          className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl"
        >
          Financial summary
        </h1>
        {/*
          The badge is a word, not a colour. §9.2: colour alone never carries meaning, and
          "these numbers can still move" is meaning.
        */}
        <Tag>{projected ? 'Projected' : 'Settled'}</Tag>
      </div>

      <p className="mt-3 max-w-[62ch] text-sm text-white/64">
        {projected
          ? 'No payout has been calculated yet, so the fees below are what today’s schedule would charge. They are priced again, against the schedule in force on the day, when the payout is prepared.'
          : 'These are the figures your payout was priced at, read from the payout itself rather than worked out again.'}
      </p>

      <StatRow className="mt-8">
        <StatBlock label="Gross" value={formatMoney(finance.gross)} />
        <StatBlock label={projected ? 'Net, projected' : 'Net'} value={formatMoney(finance.net)} />
        <StatBlock label="Paid out" value={formatMoney(finance.paidOut)} />
      </StatRow>

      <h2 className="mt-10 text-lg font-medium tracking-[-0.02em] text-white">
        What came off the gross
      </h2>
      <table className="mt-4 w-full border-collapse text-sm">
        <caption className="sr-only">
          Every deduction between what this campaign took and what is payable
        </caption>
        <tbody>
          <Row label="Gross collected" amount={formatMoney(finance.gross)} />
          <Row label="Platform fee" amount={`− ${formatMoney(finance.platformFee)}`} />
          <Row label="Processing fee" amount={`− ${formatMoney(finance.processingFee)}`} />
          <Row
            label="Tax withheld"
            amount={`− ${formatMoney(finance.taxWithheld)}`}
            note={
              finance.taxCollected
                ? undefined
                : /*
                   * A bare zero here would read as "no tax is due on your earnings", which is
                   * not something this platform is in a position to say. §4.10 is unbuilt and
                   * blocked on a legal answer, so what is true is that we withhold none.
                   */
                  'IdeaNest withholds no tax. What you owe is between you and your tax authority.'
            }
          />
          <Row label="Refunded to backers" amount={`− ${formatMoney(finance.refunded)}`} />
          <Row label={projected ? 'Payable, projected' : 'Payable'} amount={formatMoney(finance.net)} total />
        </tbody>
      </table>

      <h2 className="mt-10 text-lg font-medium tracking-[-0.02em] text-white">Payouts</h2>
      {finance.payouts.length === 0 ? (
        <p className="mt-3 max-w-[62ch] text-sm text-white/64">
          None yet. A payout is prepared after §5.4’s hold, and every one of them appears here
          — including any that were cancelled.
        </p>
      ) : (
        <ul className="mt-4 flex list-none flex-col gap-3">
          {finance.payouts.map((payout) => (
            <PayoutRow key={payout.id} payout={payout} locale={locale} />
          ))}
        </ul>
      )}

      <h2 className="mt-10 text-lg font-medium tracking-[-0.02em] text-white">
        What the books say
      </h2>
      <p className="mt-3 max-w-[62ch] text-sm text-white/64">
        §7.2’s accounts, for this campaign. Published so the totals above can be checked
        against something rather than taken on trust. A negative balance on your account is
        money the platform is holding for you.
      </p>

      {!finance.reconciled && (
        <div className="mt-4">
          {/*
            A posting that does not balance is refused by a database trigger, so this can only
            appear for a row that arrived past both the application and the trigger. It is
            therefore never noise, and it is addressed to somebody who can act on it.
          */}
          <InlineAlert variant="warning" title="These books do not balance">
            <p>
              The entries for this campaign do not sum to zero. Nothing you can do explains
              this — please contact support, quoting this campaign, before relying on the
              figures above.
            </p>
          </InlineAlert>
        </div>
      )}

      {finance.ledger.length === 0 ? (
        <p className="mt-4 text-sm text-white/64">
          Nothing has been posted for this campaign yet.
        </p>
      ) : (
        <table className="mt-4 w-full border-collapse text-sm">
          <caption className="sr-only">Ledger balances for this campaign, by account</caption>
          <thead>
            <tr className="border-b border-white/8 text-left text-white/64">
              <th scope="col" className="py-2 font-medium">
                Account
              </th>
              <th scope="col" className="py-2 text-right font-medium">
                Balance
              </th>
            </tr>
          </thead>
          <tbody>
            {finance.ledger.map((balance) => (
              <tr key={`${balance.account}-${balance.net.currency}`} className="border-b border-white/8">
                <td className="py-2 font-mono text-white/64">{balance.account}</td>
                <td className="py-2 text-right tabular-nums text-white">
                  {formatMoney(balance.net)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {finance.computedAt !== null && (
        <p className="mt-6 text-sm text-white/64">
          As of <time dateTime={finance.computedAt}>{formatExactTime(finance.computedAt, locale)}</time>.
        </p>
      )}
    </section>
  );
}

/** One line of the deduction table. `total` draws the line a reader adds up to. */
function Row({
  label,
  amount,
  note,
  total = false,
}: {
  readonly label: string;
  readonly amount: string;
  readonly note?: string | undefined;
  readonly total?: boolean;
}) {
  return (
    <tr className={total ? 'border-t border-white/16' : 'border-b border-white/8'}>
      <th scope="row" className={`py-3 text-left font-medium ${total ? 'text-white' : 'text-white/64'}`}>
        {label}
        {note !== undefined && <span className="mt-1 block text-xs font-normal text-white/64">{note}</span>}
      </th>
      <td className={`py-3 text-right tabular-nums ${total ? 'font-medium text-white' : 'text-white'}`}>
        {amount}
      </td>
    </tr>
  );
}

function PayoutRow({ payout, locale }: { readonly payout: FinancePayout; readonly locale: Parameters<typeof formatExactTime>[1] }) {
  const when = payout.sentAt ?? payout.calculatedAt;

  return (
    <li className="flex flex-wrap items-center justify-between gap-3 rounded-[14px] border border-white/8 bg-surface-2 px-4 py-3">
      <span className="flex flex-wrap items-center gap-3">
        {/* The state as a word. A coloured dot alone says nothing to a screen reader. */}
        <Tag>{PAYOUT_STATES[payout.state] ?? payout.state}</Tag>
        {when !== null && (
          <span className="text-sm text-white/64">
            <time dateTime={when}>{formatExactTime(when, locale)}</time>
          </span>
        )}
      </span>
      <span className="tabular-nums text-white">{formatMoney(payout.net)}</span>
    </li>
  );
}
