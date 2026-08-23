'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { InlineAlert, Pill, Skeleton, SkeletonGroup, Tag } from '@ideanest/ui';
import { countryName } from '../checkout/DestinationField';
import { PledgeSummary } from '../checkout/PledgeSummary';
import { getPledge, type PledgeResponse } from '../../lib/pledges/api';
import {
  findMyPledge,
  pledgeCampaignHref,
  pledgeStateLabel,
  type BackerPledgeSummary,
} from '../../lib/pledges/backer';
import { describeFailure, type CheckoutFailure } from '../../lib/pledges/failure';
import { formatExactTime } from '../../lib/time';
import { formatMoney } from '../../lib/money';
import { CancelPledgePanel } from './CancelPledgePanel';
import { PledgeEditor } from './PledgeEditor';

/**
 * One of the caller's own pledges, with §4.5's PL-09 edit and PL-10 withdrawal. Issue #287.
 *
 * <h2>Two reads, and only one of them is the authority</h2>
 *
 * `GET /v1/pledges/{id}` is the pledge: its state, its six amounts, its add-ons, its
 * supplements, its destination. Everything on this screen that anybody acts on comes from it.
 *
 * `GET /v1/me/pledges` is asked a second question and one question only — **which campaign is
 * this?** `PledgeResponse` carries a `projectId` and no title and no slugs, and there is no
 * public read keyed on a campaign id alone, so the caller's own list is the only projection
 * that can name it (`lib/pledges/backer.ts` carries the argument and the bound). It is
 * best-effort: a pledge whose campaign could not be named still renders, still edits, and
 * still withdraws, because refusing to show somebody their own pledge over a missing heading
 * would be the worse failure.
 *
 * <h2>Whether the controls appear is decided by the PLEDGE's state, and not by the
 * campaign's</h2>
 *
 * `PledgeService#requireEditable` composes two facts owned by two different modules: the
 * pledge is `DRAFT` or `CONFIRMED`, **and** the campaign is still accepting pledges. This
 * component checks the first and deliberately does not check the second.
 *
 * The reason is that the client cannot check it correctly. `PledgeAcceptance` accepts a `LIVE`
 * campaign before its deadline and also a `LATE_PLEDGE` one inside a window it opened (PL-16),
 * and the summary above carries a state and a deadline but not the late-pledge window. A
 * client-side approximation of that rule would be a second rule, free to drift from the
 * service's, and its failure mode is the bad one: hiding the withdrawal control from somebody
 * who is still entitled to withdraw.
 *
 * So the controls are shown and the service decides. A campaign that has closed answers
 * `PROJECT_NOT_LIVE` — the same code, body and `meta.deadline` the draft endpoint gives — and
 * `lib/pledges/failure.ts` words it as "this campaign is not taking pledges. Nothing has
 * changed", which is an answer a backer can read. A control that is refused with a sentence is
 * better than a control that was never there.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5: pledge and checkout are "near zero — every animation here reads as
 * hesitation". Nothing on this screen enters, fades or slides, including the withdrawal
 * confirmation, which is an inline disclosure rather than a modal for that reason.
 */

/** §6.2's two editable states. The service's `PledgeState.EDITABLE`, and nothing more. */
const EDITABLE = new Set(['DRAFT', 'CONFIRMED']);

type Status = 'loading' | 'ready' | 'failed';

export interface PledgeManagerProps {
  readonly pledgeId: string;
}

export function PledgeManager({ pledgeId }: PledgeManagerProps) {
  const [status, setStatus] = useState<Status>('loading');
  const [pledge, setPledge] = useState<PledgeResponse | null>(null);
  const [summary, setSummary] = useState<BackerPledgeSummary | null>(null);
  const [failure, setFailure] = useState<CheckoutFailure | null>(null);

  const load = useCallback(
    async (signal?: AbortSignal): Promise<void> => {
      /* A function rather than a repeated expression: `signal.aborted` is mutated by the
         controller between awaits, so a narrowing the compiler carries forward from the first
         check would be a fact that stopped being true while the request was in flight. */
      const abandoned = (): boolean => signal !== undefined && signal.aborted;

      try {
        const current = await getPledge(pledgeId, signal);
        if (abandoned()) return;
        setPledge(current);
        setStatus('ready');
      } catch (cause) {
        if (abandoned()) return;
        setFailure(describeFailure(cause));
        setStatus('failed');
        return;
      }

      try {
        const found = await findMyPledge(pledgeId, signal);
        if (abandoned()) return;
        setSummary(found);
      } catch {
        /* Best-effort only: the heading is a convenience and the pledge above is the fact.
           A failure here must not take down a screen that has already loaded. */
        setSummary(null);
      }
    },
    [pledgeId],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const display = useMemo(() => {
    try {
      return new Intl.DisplayNames(['en'], { type: 'region' });
    } catch {
      // A runtime without region display names shows the code, which is still an answer.
      return null;
    }
  }, []);

  if (status === 'loading') {
    return (
      <SkeletonGroup label="Loading this pledge" className="flex flex-col gap-4">
        <Skeleton height="8rem" />
        <Skeleton height="14rem" />
      </SkeletonGroup>
    );
  }

  if (status === 'failed' || pledge === null) {
    return (
      <div className="flex flex-col gap-6">
        <InlineAlert variant="danger" title={failure?.title ?? 'This pledge could not be read'}>
          <p>{failure?.detail ?? 'The service did not answer. Try again in a moment.'}</p>
        </InlineAlert>
        <div>
          <Link href="/pledges">
            <Pill type="button" variant="outline">
              All your pledges
            </Pill>
          </Link>
        </div>
      </div>
    );
  }

  const editable = EDITABLE.has(pledge.state);
  const rewardTitle = summary?.rewardTitle ?? null;
  const campaignTitle = summary?.project.title ?? null;
  const destination =
    pledge.shippingCountry == null ? null : countryName(pledge.shippingCountry, display);

  return (
    <div className="flex flex-col gap-6">
      <section className="rounded-2xl border border-white/8 bg-surface-2 p-6 sm:p-8">
        <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
          <div className="min-w-0">
            <h2 className="text-lg font-medium tracking-[-0.02em] text-white">
              {summary === null ? (
                /* The campaign could not be named — see the module comment. Saying so is
                   better than printing an identifier nobody can read. */
                'This pledge’s campaign could not be loaded'
              ) : (
                <Link
                  href={pledgeCampaignHref(summary.project)}
                  className="rounded-sm hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                >
                  {summary.project.title}
                </Link>
              )}
            </h2>

            <div className="mt-3 flex flex-wrap gap-2">
              <Tag>{pledgeStateLabel(pledge.state)}</Tag>
              {pledge.isAnonymous && <Tag>Anonymous</Tag>}
              {pledge.latePledge && <Tag>Late pledge</Tag>}
            </div>

            {pledge.confirmedAt != null && (
              <p className="mt-3 text-sm text-white/40">
                Confirmed {formatExactTime(pledge.confirmedAt)}
              </p>
            )}
            {pledge.canceledAt != null && (
              <p className="mt-3 text-sm text-white/40">
                Withdrawn {formatExactTime(pledge.canceledAt)}
              </p>
            )}
          </div>
        </div>

        <div className="mt-6">
          <PledgeSummary
            amounts={pledge.amounts}
            /* The service's figures, always. This screen never previews. */
            source="quoted"
            rewardTitle={rewardTitle}
            destination={destination}
          >
            {destination !== null && (
              <p className="text-sm text-on-white/64">
                <Link
                  href={`/pledges/${encodeURIComponent(pledge.id)}/address`}
                  className="rounded-sm underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                >
                  Where this reward is going
                </Link>
              </p>
            )}
          </PledgeSummary>
        </div>

        {pledge.supplements.length > 0 && (
          <div className="mt-6 border-t border-white/6 pt-6">
            <h3 className="text-[15px] font-medium text-white">Bought after the campaign closed</h3>
            {/*
              §4.8's PM-09 and PM-10 are charged separately and are NOT part of the total above:
              V29 froze the comparison §5.1 made at the deadline, so a later purchase cannot be
              folded back into the pledge. Printing them beside the total rather than inside it
              is the visible half of that decision.
            */}
            <ul className="mt-3 flex list-none flex-col gap-2 text-sm">
              {pledge.supplements.map((supplement) => (
                <li key={supplement.id} className="flex items-baseline justify-between gap-4">
                  <span className="text-white/64">
                    {supplement.kind === 'UPGRADE' ? 'Reward upgrade' : 'Extra add-ons'} ·{' '}
                    {formatExactTime(supplement.createdAt)}
                  </span>
                  <span className="tabular-nums text-white">{formatMoney(supplement.amount)}</span>
                </li>
              ))}
            </ul>
            <p className="mt-3 text-xs text-white/40">
              Charged separately from the pledge above, and not yet charged at all.
            </p>
          </div>
        )}
      </section>

      {editable ? (
        <>
          <PledgeEditor pledge={pledge} onSaved={setPledge} />
          <CancelPledgePanel
            pledge={pledge}
            campaignTitle={campaignTitle}
            rewardTitle={rewardTitle}
            /* Re-read rather than patch a state in: a cancellation answers 204 with no body,
               so the only way to know what the pledge is now is to ask. */
            onCancelled={() => void load()}
          />
        </>
      ) : (
        <InlineAlert variant="info" title="This pledge can no longer be changed here">
          <p>
            A pledge can be edited or withdrawn while it is being made and after it is confirmed,
            up to the campaign’s deadline. This one is{' '}
            {pledgeStateLabel(pledge.state).toLowerCase()}, so those controls are not offered. If
            something about it is wrong, the campaign’s creator is who to ask.
          </p>
        </InlineAlert>
      )}

      <div>
        <Link href="/pledges">
          <Pill type="button" variant="outline">
            All your pledges
          </Pill>
        </Link>
      </div>
    </div>
  );
}
