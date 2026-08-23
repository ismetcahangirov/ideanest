'use client';

import { useEffect, useRef, useState } from 'react';
import { InlineAlert, Pill } from '@ideanest/ui';
import { cancelPledge, type PledgeResponse } from '../../lib/pledges/api';
import { describeFailure, type CheckoutFailure } from '../../lib/pledges/failure';
import { IdempotencyKeyring } from '../../lib/pledges/idempotency';
import { formatMoney } from '../../lib/money';

/**
 * §4.5's PL-10 — the backer withdraws, and the reward's place goes back. Issue #287.
 *
 * <h2>The confirmation states what is released, because that is what is irreversible</h2>
 *
 * Nothing is refunded by a cancellation, for the good reason that nothing was collected
 * (§9.7): §9.2 moves no money at confirmation and collection is epic #59's. So the destructive
 * part of this action is not financial at all — **it is the stock.** PL-10 releases every place
 * the pledge was holding: the reward tier's, and each add-on's quantity (#203), from whichever
 * counter was holding them. That stock immediately becomes available to somebody else, and on
 * a limited tier it will be taken.
 *
 * A confirmation that said only "are you sure?" would therefore be confirming the wrong thing.
 * The panel names the campaign, names the tier, and says in as many words that the place goes
 * back — and it says the amount is not a charge being avoided, because a backer who believes
 * they are cancelling a payment has misunderstood what they are doing.
 *
 * <h2>Two steps, inline, and deliberately not a modal</h2>
 *
 * `Modal` lives behind `@ideanest/ui/motion` — 116 kB of animation runtime — and it animates
 * on entry. docs/motion-system.md §5 gives this surface "near zero: every animation here reads
 * as hesitation", and §4.11.1's 200ms modal entry is 200ms between somebody deciding and being
 * able to act. An inline disclosure costs neither: the confirmation is already in the
 * document, it appears at once, and the page behind it is not something anybody needs
 * protecting from.
 *
 * What is kept from the modal contract is the part that matters — focus moves to the
 * confirmation when it opens, so a keyboard reader is taken to the question rather than left
 * on a button that has changed what the page says. `role="group"` with a name, rather than
 * `role="alertdialog"`: this does not trap focus and does not stop the page, and claiming a
 * dialog role for something that does neither is a promise to assistive technology that the
 * component does not keep.
 *
 * <h2>The safe button is the plain one</h2>
 *
 * "Keep my pledge" is a ghost pill and "Withdraw" is `--danger` with an icon-free label that
 * says what it does. Neither is lime: lime means *act now* and the interface has no opinion
 * about which of these two somebody should press. Colour never carries the meaning on its own
 * (docs/ui-kit.md §9.2) — the words do, and `--danger` only raises them.
 */

export interface CancelPledgePanelProps {
  readonly pledge: PledgeResponse;
  /** The campaign's title, when the page was able to find it. */
  readonly campaignTitle: string | null;
  /** The reward tier's title, or null for PL-02's support-only pledge. */
  readonly rewardTitle: string | null;
  /** Called once the service has answered 204. The page re-reads the pledge. */
  readonly onCancelled: () => void;
}

export function CancelPledgePanel({
  pledge,
  campaignTitle,
  rewardTitle,
  onCancelled,
}: CancelPledgePanelProps) {
  const [confirming, setConfirming] = useState(false);
  const [working, setWorking] = useState(false);
  const [failure, setFailure] = useState<CheckoutFailure | null>(null);

  const heading = useRef<HTMLParagraphElement>(null);
  const keyring = useRef(new IdempotencyKeyring());

  useEffect(() => {
    if (confirming) heading.current?.focus();
  }, [confirming]);

  const addonItems = pledge.addons.reduce((total, addon) => total + addon.quantity, 0);

  async function withdraw(): Promise<void> {
    if (working) return;

    setWorking(true);
    setFailure(null);
    try {
      /*
       * The key is minted for this pledge's cancellation and reused across retries: a
       * cancellation whose response was lost on the way back is replayed from the service's
       * record rather than sent as a second withdrawal. There is no body, so the pledge id is
       * the whole of what identifies the intent.
       */
      await cancelPledge(pledge.id, keyring.current.keyFor({ cancel: pledge.id }));
      onCancelled();
      setConfirming(false);
    } catch (cause) {
      const described = describeFailure(cause);
      if (described.retireKey) keyring.current.retire({ cancel: pledge.id });
      setFailure(described);
    } finally {
      setWorking(false);
    }
  }

  return (
    <section className="rounded-2xl border border-white/8 bg-surface-2 p-6 sm:p-8">
      <h2 className="text-lg font-medium tracking-[-0.02em] text-white">Withdraw this pledge</h2>
      <p className="mt-2 max-w-[62ch] text-[15px] leading-relaxed text-white/64">
        You can withdraw until the campaign’s deadline. Nothing is refunded because nothing has
        been charged — what a withdrawal does is give back the place your pledge is holding.
      </p>

      {!confirming ? (
        <div className="mt-6">
          <Pill type="button" variant="outline" onClick={() => setConfirming(true)}>
            Withdraw this pledge
          </Pill>
        </div>
      ) : (
        <div
          role="group"
          aria-label="Confirm withdrawing this pledge"
          className="mt-6 rounded-xl border border-danger/40 bg-surface-3 p-5"
        >
          {/*
            `tabIndex={-1}` so focus can be moved here programmatically without adding a tab
            stop of its own. What a reader hears on arrival is the consequence, not the word
            "confirm".
          */}
          <p
            ref={heading}
            tabIndex={-1}
            className="text-[15px] font-medium text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            Withdrawing gives your place back to the campaign
          </p>

          <ul className="mt-3 flex list-disc flex-col gap-2 pl-5 text-sm text-white/64">
            <li>
              {rewardTitle === null ? (
                <>This pledge holds no reward tier, so nothing is set aside for it.</>
              ) : (
                <>
                  The place this pledge holds on <span className="text-white">{rewardTitle}</span>{' '}
                  goes back to the campaign, and somebody else can take it. On a limited tier,
                  somebody will.
                </>
              )}
            </li>

            {addonItems > 0 && (
              <li>
                {addonItems === 1
                  ? 'The one add-on item this pledge reserved goes back too.'
                  : `The ${addonItems} add-on items this pledge reserved go back too.`}
              </li>
            )}

            <li>
              {/*
                The amount is named so the sentence is checkable, and it is named as something
                that is NOT being cancelled — §9.2 moves no money at confirmation, so there is
                no charge here to avoid. A backer who thinks they are stopping a payment has
                misunderstood what they are doing.
              */}
              Nothing is refunded: the {formatMoney(pledge.amounts.total)} on this pledge has not
              been charged, and will not be.
            </li>

            <li>
              {campaignTitle === null
                ? 'To back this campaign again afterwards you would make a new pledge, at whatever is still available.'
                : `To back ${campaignTitle} again afterwards you would make a new pledge, at whatever is still available.`}
            </li>
          </ul>

          {failure !== null && (
            <div className="mt-4">
              <InlineAlert variant="danger" title={failure.title}>
                <p>{failure.detail}</p>
              </InlineAlert>
            </div>
          )}

          <div className="mt-5 flex flex-wrap gap-3">
            <Pill
              type="button"
              variant="danger"
              disabled={working}
              onClick={() => void withdraw()}
            >
              {working ? 'Withdrawing' : 'Yes, withdraw my pledge'}
            </Pill>
            <Pill
              type="button"
              variant="ghost"
              disabled={working}
              onClick={() => {
                setConfirming(false);
                setFailure(null);
              }}
            >
              Keep my pledge
            </Pill>
          </div>
        </div>
      )}
    </section>
  );
}
