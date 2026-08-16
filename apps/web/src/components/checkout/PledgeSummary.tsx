'use client';

import Decimal from 'decimal.js';
import type { ReactNode } from 'react';
import { FloatingPanel } from '@ideanest/ui';
import { formatMoney, type Money } from '../../lib/money';
import type { PledgeAmounts } from '../../lib/pledges/api';

/**
 * PL-06: what this pledge comes to, broken into its lines, always on screen.
 *
 * <h2>Why this is the white surface</h2>
 *
 * docs/ui-kit.md §8.5 makes checkout "the one screen where a white surface
 * dominates": somebody about to part with money wants maximum clarity and a
 * familiar context, and near-black on white gives both. It is the SUMMARY that
 * takes it rather than the whole page, for a reason the system itself decides —
 * every form primitive in `@ideanest/ui` is built for the dark ground (a `Radio`
 * label is `text-white`, an input is `--surface-3`), so putting the controls on
 * white would mean reimplementing them, which CLAUDE.md §2 rules out and §7.13
 * gives no white variants for. §8.3 already sends "financial summary" to the
 * floating panel, so this is the shape the system had in mind.
 *
 * Text inside is near-black, so `--text-secondary` does not apply here (§7.9).
 * Every muted tone is `text-on-white` at reduced opacity.
 *
 * <h2>Two sources, one renderer</h2>
 *
 * The panel takes `PledgeAmounts` and does not know whether it is showing the
 * client's preview or the server's quote. That is the point: a preview drawn
 * differently from the real thing would read as two different numbers, and the
 * one thing this panel must never do is make a backer wonder which figure is the
 * one that counts. What changes with the source is a sentence, not the money.
 *
 * <h2>Motion: none</h2>
 *
 * The total changes as the selection changes and it changes without a transition.
 * docs/motion-system.md §5 puts checkout at "near zero — 150ms step change and a
 * loading indicator", and a number that eases into place on the screen where
 * somebody is deciding whether to pay reads as hesitation about the amount.
 */

export interface PledgeSummaryProps {
  /** The six amounts, or null before there is anything to price. */
  amounts: PledgeAmounts | null;
  /** `preview` is the client's arithmetic; `quoted` is the server's answer. */
  source: 'preview' | 'quoted';
  /** The chosen tier's title, or null for a pledge with no reward. */
  rewardTitle: string | null;
  /** Where it is going, already turned into a name. */
  destination?: string | null;
  /** Shown in place of the lines when the selection cannot be priced. */
  unavailable?: ReactNode;
  /** The action for this step, and any note beneath it. */
  children?: ReactNode;
}

function isZero(money: Money): boolean {
  // `Decimal`, not `parseFloat`: this decides whether a line is printed, and a
  // module that reads money with `Number()` is one that has stopped being able
  // to trust its own strings (`lib/money.ts`).
  return new Decimal(money.amount).isZero();
}

function Line({ label, money, muted = false }: { label: string; money: Money; muted?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-4 text-sm">
      <span className={muted ? 'text-on-white/64' : 'text-on-white'}>{label}</span>
      <span className="tabular-nums text-on-white">{formatMoney(money)}</span>
    </div>
  );
}

export function PledgeSummary({
  amounts,
  source,
  rewardTitle,
  destination = null,
  unavailable = null,
  children,
}: PledgeSummaryProps) {
  return (
    <FloatingPanel title="Your pledge">
      <p className="text-sm text-on-white/64">
        {rewardTitle ?? 'Support with no reward'}
        {destination != null && <> · to {destination}</>}
      </p>

      {unavailable != null ? (
        <div className="mt-4 text-sm text-on-white/64">{unavailable}</div>
      ) : amounts === null ? (
        <p className="mt-4 text-sm text-on-white/64">
          Choose a reward, or choose to pledge without one, and the total appears here.
        </p>
      ) : (
        <div className="mt-4 flex flex-col gap-2">
          {/* The base line is always printed, even at the tier's bare price with
              nothing else: a total with no lines under it is a figure a backer
              cannot check. */}
          <Line label={rewardTitle === null ? 'Your support' : 'Reward'} money={amounts.base} />

          {!isZero(amounts.addons) && <Line label="Add-ons" money={amounts.addons} />}
          {!isZero(amounts.bonus) && <Line label="Bonus support" money={amounts.bonus} />}
          {!isZero(amounts.shipping) && <Line label="Delivery" money={amounts.shipping} />}

          {/*
            TAX IS PRINTED ONLY WHEN IT IS NOT ZERO, and it is zero on every
            pledge this platform can take today — `TaxPolicy` holds the zero
            deliberately until #78 builds a tax model. A permanent "Tax 0.00"
            line invites the one question the interface cannot answer ("tax on
            what?") and would go on inviting it for however long #78 takes. The
            line appears by itself the day the policy stops answering zero.
          */}
          {!isZero(amounts.tax) && <Line label="Tax" money={amounts.tax} />}

          <div className="mt-1 border-t border-black/10 pt-3">
            <div className="flex items-baseline justify-between gap-4">
              <span className="font-medium text-on-white">Total</span>
              <span className="text-lg font-medium tabular-nums text-on-white">
                {formatMoney(amounts.total)}
              </span>
            </div>
          </div>

          <p className="mt-1 text-[13px] text-on-white/64">
            {source === 'preview'
              ? 'An estimate from the prices on this page. The campaign confirms the amounts when it reserves your reward.'
              : 'Confirmed by the campaign when it reserved your reward.'}
          </p>
        </div>
      )}

      {children != null && <div className="mt-5 flex flex-col gap-3">{children}</div>}
    </FloatingPanel>
  );
}
