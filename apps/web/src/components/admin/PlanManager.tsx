'use client';

import { useState } from 'react';
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
  activateSubscription,
  addPlan,
  cancelSubscriptionAsStaff,
  changePlan,
  readPlanCatalogue,
  readSubscriptions,
  type BillingPeriod,
  type ConsoleSubscription,
  type Plan,
} from '../../lib/admin/plans';
import { consoleMessageFor, shortId } from '../../lib/admin/refusals';
import { fillPlaceholders } from '../../lib/i18n/placeholders';
import type { PlanManagerCopy } from '../../lib/i18n/admin/money-copy';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const PERIODS: readonly BillingPeriod[] = ['MONTHLY', 'YEARLY'];

/**
 * AD-11's second screen: the plans a creator publishes under, and who is on them.
 *
 * <h2>Two things on one screen, and they are not two screens</h2>
 *
 * The catalogue and the payment queue are here together because they are one job. Until a
 * payment provider is integrated, a plan becomes real when somebody on this screen records
 * that a transfer arrived — so the person who edits a price is the person who confirms the
 * money against it, and splitting the two would mean holding one open to work the other.
 *
 * <h2>A plan is edited in place, and the screen says why that is safe</h2>
 *
 * The fee editor next door refuses to edit anything, because a payout was priced against a
 * rate and editing one would rewrite what a past payout should have been. This screen edits
 * freely, and an operator moving between the two is entitled to know what changed: a
 * subscriber's price is copied onto their own subscription at purchase, so nothing here can
 * reach backwards into a bill.
 *
 * <p><strong>The limits are the exception and the notice says so.</strong> They are read live,
 * so lowering one applies to everybody on the plan — at their next submission, not
 * retroactively. That is the one consequence on this screen an operator cannot see from the
 * form.
 *
 * <h2>Unlisting, not deleting</h2>
 *
 * There is no delete control because there is no delete endpoint. Removing a plan would
 * either orphan its subscribers or take their subscription with it, and unlisting stops it
 * being sold while leaving everybody on it where they are.
 *
 * MOTION: none, like every console screen. `docs/motion-system.md` §5.
 */
export interface PlanManagerProps {
  readonly copy: PlanManagerCopy;
}

export function PlanManager({ copy }: PlanManagerProps) {
  const catalogue = useConsoleResource(
    (signal) => readPlanCatalogue(signal),
    copy.subject,
    copy.refusals,
    [],
  );

  const [queueOnly, setQueueOnly] = useState(true);
  const queue = useConsoleResource(
    (signal) => readSubscriptions(queueOnly, signal),
    copy.subject,
    copy.refusals,
    [queueOnly],
  );

  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [price, setPrice] = useState('19.00');
  const [period, setPeriod] = useState<BillingPeriod>('MONTHLY');
  const [maxActive, setMaxActive] = useState('1');
  const [goalCeiling, setGoalCeiling] = useState('');

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [written, setWritten] = useState<string | null>(null);

  if (catalogue.status === 'signed-out' || catalogue.status === 'forbidden') {
    return <ConsoleRefusal status={catalogue.status} subject={copy.subject} copy={copy.refusals} />;
  }

  async function add(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    if (code.trim() === '' || name.trim() === '') return;

    setBusy(true);
    setError(null);
    setWritten(null);
    try {
      const plan = await addPlan({
        code: code.trim().toUpperCase(),
        name: name.trim(),
        description: description.trim() === '' ? null : description.trim(),
        price: price.trim(),
        currency: 'AZN',
        billingPeriod: period,
        // An empty field is no limit, which is the same thing the wire means by null.
        maxActiveCampaigns: maxActive.trim() === '' ? null : Number(maxActive.trim()),
        goalCeiling: goalCeiling.trim() === '' ? null : goalCeiling.trim(),
        sortOrder: (catalogue.data?.plans.length ?? 0) * 10 + 10,
      });

      setWritten(fillPlaceholders(copy.addedNotice, { plan: plan.name }));
      setCode('');
      setName('');
      setDescription('');
      catalogue.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  async function setListed(plan: Plan, listed: boolean): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await changePlan({ planId: plan.id, listed });
      catalogue.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  async function reprice(plan: Plan, next: string): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await changePlan({ planId: plan.id, price: next.trim() });
      setWritten(fillPlaceholders(copy.repricedNotice, { plan: plan.name }));
      catalogue.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  async function recordPayment(subscription: ConsoleSubscription, note: string): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await activateSubscription(subscription.id, note);
      setWritten(fillPlaceholders(copy.activatedNotice, { id: shortId(subscription.id) }));
      queue.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  async function end(subscription: ConsoleSubscription, reason: string): Promise<void> {
    if (reason.trim() === '') return;

    setBusy(true);
    setError(null);
    try {
      await cancelSubscriptionAsStaff(subscription.id, reason.trim());
      setWritten(fillPlaceholders(copy.endedNotice, { id: shortId(subscription.id) }));
      queue.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  const plans = catalogue.data?.plans ?? [];
  const subscriptions = queue.data?.subscriptions ?? [];

  return (
    <div className="flex flex-col gap-10">
      <InlineAlert variant="info" title={copy.noticeTitle}>
        {copy.noticeBody}
      </InlineAlert>

      {/* ---- the catalogue ------------------------------------------------ */}

      <section aria-labelledby="plan-catalogue-heading">
        <h2 id="plan-catalogue-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.catalogueHeading}
        </h2>
        <p className="mt-2 max-w-[62ch] text-sm text-white/64">{copy.catalogueIntro}</p>

        {catalogue.status === 'loading' && (
          <SkeletonGroup label={copy.loadingCatalogue} className="mt-4">
            <Skeleton height="1rem" width="40%" />
            <Skeleton height="0.875rem" width="60%" className="mt-3" />
          </SkeletonGroup>
        )}

        {catalogue.status === 'failed' && (
          <>
            <InlineAlert variant="danger" title={copy.errorTitle} className="mt-4">
              {catalogue.error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={catalogue.reload}>
              {copy.tryAgain}
            </Pill>
          </>
        )}

        {catalogue.status === 'ready' && plans.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title={copy.emptyCatalogueTitle}
            description={copy.emptyCatalogueBody}
          />
        )}

        {catalogue.status === 'ready' && plans.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-3">
            {plans.map((plan) => (
              <PlanRow
                key={plan.id}
                plan={plan}
                copy={copy}
                busy={busy}
                onList={(listed) => void setListed(plan, listed)}
                onReprice={(next) => void reprice(plan, next)}
              />
            ))}
          </ul>
        )}
      </section>

      {/* ---- adding one --------------------------------------------------- */}

      <section aria-labelledby="plan-add-heading">
        <h2 id="plan-add-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.addHeading}
        </h2>

        <form onSubmit={(event) => void add(event)} className="mt-4 flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <Field label={copy.codeLabel} hint={copy.codeHint} className="min-w-[180px]">
              <TextInput value={code} onChange={(event) => setCode(event.target.value)} maxLength={40} />
            </Field>
            <Field label={copy.nameLabel} className="min-w-[220px] flex-1">
              <TextInput value={name} onChange={(event) => setName(event.target.value)} maxLength={120} />
            </Field>
          </div>

          <Field label={copy.descriptionLabel} hint={copy.descriptionHint}>
            <TextInput
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={2000}
            />
          </Field>

          <div className="flex flex-wrap items-end gap-3">
            <Field label={copy.priceLabel} hint={copy.priceHint} className="min-w-[160px]">
              <TextInput
                inputMode="decimal"
                value={price}
                onChange={(event) => setPrice(event.target.value)}
              />
            </Field>

            <Field label={copy.periodLabel} className="min-w-[160px]">
              <Select value={period} onChange={(event) => setPeriod(event.target.value as BillingPeriod)}>
                {PERIODS.map((option) => (
                  <option key={option} value={option}>
                    {copy.period[option]}
                  </option>
                ))}
              </Select>
            </Field>

            <Field label={copy.maxActiveLabel} hint={copy.unlimitedHint} className="min-w-[180px]">
              <TextInput
                inputMode="numeric"
                value={maxActive}
                onChange={(event) => setMaxActive(event.target.value)}
              />
            </Field>

            <Field label={copy.goalCeilingLabel} hint={copy.unlimitedHint} className="min-w-[180px]">
              <TextInput
                inputMode="decimal"
                value={goalCeiling}
                onChange={(event) => setGoalCeiling(event.target.value)}
              />
            </Field>
          </div>

          <div>
            <Pill type="submit" variant="outline" size="sm" disabled={busy}>
              {busy ? copy.saving : copy.addPlan}
            </Pill>
          </div>
        </form>
      </section>

      {/* ---- the payment queue -------------------------------------------- */}

      <section aria-labelledby="plan-queue-heading">
        <h2 id="plan-queue-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.queueHeading}
        </h2>
        <p className="mt-2 max-w-[62ch] text-sm text-white/64">{copy.queueIntro}</p>

        <Pill
          variant="ghost"
          size="sm"
          className="mt-4"
          onClick={() => setQueueOnly((only) => !only)}
          aria-pressed={!queueOnly}
        >
          {queueOnly ? copy.showAll : copy.showQueue}
        </Pill>

        {queue.status === 'loading' && (
          <SkeletonGroup label={copy.loadingQueue} className="mt-4">
            <Skeleton height="1rem" width="40%" />
          </SkeletonGroup>
        )}

        {queue.status === 'failed' && (
          <InlineAlert variant="danger" title={copy.errorTitle} className="mt-4">
            {queue.error}
          </InlineAlert>
        )}

        {queue.status === 'ready' && subscriptions.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title={copy.emptyQueueTitle}
            description={copy.emptyQueueBody}
          />
        )}

        {queue.status === 'ready' && subscriptions.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-3">
            {subscriptions.map((subscription) => (
              <SubscriptionRow
                key={subscription.id}
                subscription={subscription}
                copy={copy}
                busy={busy}
                onActivate={(note) => void recordPayment(subscription, note)}
                onEnd={(reason) => void end(subscription, reason)}
              />
            ))}
          </ul>
        )}
      </section>

      {written && (
        <InlineAlert variant="success" title={copy.doneTitle}>
          {written}
        </InlineAlert>
      )}
      {error && (
        <InlineAlert variant="danger" title={copy.failedTitle}>
          {error}
        </InlineAlert>
      )}
    </div>
  );
}

/* -------------------------------------------------------------------------
 * One plan
 * ---------------------------------------------------------------------- */

interface PlanRowProps {
  plan: Plan;
  copy: PlanManagerCopy;
  busy: boolean;
  onList: (listed: boolean) => void;
  onReprice: (price: string) => void;
}

function PlanRow({ plan, copy, busy, onList, onReprice }: PlanRowProps) {
  const [next, setNext] = useState(plan.price);

  return (
    <li className="rounded-lg border border-white/8 bg-surface-2 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[15px] text-white">
            {plan.name} <span className="text-white/40">{plan.code}</span>
          </p>
          <p className="mt-1 text-[13px] text-white/64">
            {fillPlaceholders(copy.planSummary, {
              price: `${plan.price} ${plan.currency}`,
              period: copy.period[plan.billingPeriod],
              campaigns:
                plan.maxActiveCampaigns == null
                  ? copy.unlimited
                  : String(plan.maxActiveCampaigns),
              ceiling: plan.goalCeiling == null ? copy.unlimited : `${plan.goalCeiling} ${plan.currency}`,
            })}
          </p>
        </div>

        {/*
          A tag as well as a word: an unlisted plan differs from a listed one by nothing a
          reader can see otherwise, and colour alone would carry the meaning (ui-kit §9.2).
        */}
        <Tag variant={plan.listed ? 'success' : 'default'}>
          {plan.listed ? copy.onSale : copy.retired}
        </Tag>
      </div>

      <div className="mt-3 flex flex-wrap items-end gap-3">
        <Field label={copy.repriceLabel} className="min-w-[160px]">
          <TextInput inputMode="decimal" value={next} onChange={(event) => setNext(event.target.value)} />
        </Field>

        <Pill variant="ghost" size="sm" disabled={busy} onClick={() => onReprice(next)}>
          {copy.reprice}
        </Pill>

        <Pill variant="ghost" size="sm" disabled={busy} onClick={() => onList(!plan.listed)}>
          {plan.listed ? copy.retire : copy.putOnSale}
          <span className="sr-only">: {plan.name}</span>
        </Pill>
      </div>
    </li>
  );
}

/* -------------------------------------------------------------------------
 * One subscription
 * ---------------------------------------------------------------------- */

interface SubscriptionRowProps {
  subscription: ConsoleSubscription;
  copy: PlanManagerCopy;
  busy: boolean;
  onActivate: (note: string) => void;
  onEnd: (reason: string) => void;
}

function SubscriptionRow({ subscription, copy, busy, onActivate, onEnd }: SubscriptionRowProps) {
  // Two fields, two pieces of state. One shared string would put whatever somebody typed as
  // a reason for ending a subscription into the payment reference of the one above it.
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const pending = subscription.state === 'PENDING_PAYMENT';

  return (
    <li className="rounded-lg border border-white/8 bg-surface-2 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[15px] text-white">
            {subscription.planName ?? subscription.planCode ?? copy.unknownPlan}{' '}
            <span className="text-white/40">{shortId(subscription.accountId)}</span>
          </p>
          <p className="mt-1 text-[13px] text-white/64">
            {fillPlaceholders(copy.subscriptionSummary, {
              price: `${subscription.price} ${subscription.currency}`,
              until: subscription.currentPeriodEnd?.slice(0, 10) ?? copy.notStarted,
            })}
          </p>
        </div>

        <Tag variant={subscription.entitled ? 'success' : pending ? 'warning' : 'default'}>
          {copy.state[subscription.state]}
        </Tag>
      </div>

      {pending && (
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <Field label={copy.noteLabel} hint={copy.noteHint} className="min-w-[260px] flex-1">
            <TextInput value={note} onChange={(event) => setNote(event.target.value)} maxLength={2000} />
          </Field>
          <Pill variant="outline" size="sm" disabled={busy} onClick={() => onActivate(note.trim())}>
            {copy.recordPayment}
          </Pill>
        </div>
      )}

      {subscription.state !== 'CANCELED' && (
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <Field label={copy.endReasonLabel} hint={copy.endReasonHint} className="min-w-[260px] flex-1">
            <TextInput value={reason} onChange={(event) => setReason(event.target.value)} maxLength={2000} />
          </Field>
          <Pill variant="ghost" size="sm" disabled={busy} onClick={() => onEnd(reason)}>
            {copy.endSubscription}
          </Pill>
        </div>
      )}
    </li>
  );
}
