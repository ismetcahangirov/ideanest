'use client';

import { useEffect, useMemo, useRef } from 'react';
import {
  Checkbox,
  Field,
  InlineAlert,
  Pill,
  Skeleton,
  SkeletonGroup,
  TextInput,
} from '@ideanest/ui';
import { formatMoney, type AmountRejection } from '../../lib/money';
import type { PledgeResponse, PublicReward } from '../../lib/pledges/api';
import type { CheckoutFailure } from '../../lib/pledges/failure';
import { destinationOptions, toAmounts, type QuoteRefusal } from '../../lib/pledges/quote';
import { AddonChoice } from './AddonChoice';
import { countryName, DestinationField } from './DestinationField';
import { PaymentStep } from './PaymentStep';
import { PledgeSummary } from './PledgeSummary';
import { RewardChoice } from './RewardChoice';
import { NO_REWARD, useCheckout } from './useCheckout';
import { useReservationClock } from './useReservationClock';
import { type CheckoutCopy, fillPlaceholders } from '../../lib/i18n/checkout-copy';

/**
 * The checkout — docs/architecture.md §4.5, PL-01 to PL-08 and PL-12.
 *
 * <h2>Three steps, one route</h2>
 *
 * The campaign editor makes each of its sections a route, because a creator
 * returns to one, links to one, and reloads one. Checkout is the opposite case,
 * and `useCheckout` gives the argument in full: a half-made pledge is not a thing
 * to link to, and the back button must not be able to re-enter a reservation that
 * has expired or a confirmation that has already happened. So the steps are
 * steps, the address does not change, and there is no query string to reconstruct
 * a stale selection from.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 puts "pledge and checkout" at **near zero — 150ms step
 * change and a loading indicator**, and its own rule underneath: when the user is
 * spending money, motion decreases. So there is no `FadeUp` on this surface —
 * Discovery's one entry animation stops at Discovery — nothing enters, nothing
 * staggers, and the countdown changes its number without moving (§6: "The
 * countdown must not animate"). The only movement anywhere here is the 150ms
 * colour change the primitives already carry.
 *
 * §6 does list a 600ms checkmark draw for "pledge confirmed". It is not used, and
 * that is a decision rather than an oversight: §5's per-surface budget is the one
 * that governs — it is written specifically to overrule the pattern catalogue on
 * the surfaces where money is at stake — and 600ms of drawing is not "near zero".
 * The confirmation states its outcome in words and a static glyph.
 *
 * <h2>Where the lime is</h2>
 *
 * Exactly one lime element, and only on the step that has it: the confirm button
 * (§8.5). The selection step has no accent pill at all — its primary action is
 * white, because "continue" is not urgent — and the selected tier's lime is the
 * radio's, which is where §7.13 puts it. Success on the last step is `--success`
 * and never lime: a backer who reads lime as "done" has been told the opposite of
 * the truth (§2.4).
 */

/** Which of the three steps the backer is on. */
type Step = 1 | 2 | 3;

/*
 * A function of the copy rather than a module constant. The names are words now, and words
 * belong to the request rather than to the module: a constant here would be resolved once,
 * in whatever language happened to render first, and then shown to everybody.
 */
function stepNames(copy: CheckoutCopy): Readonly<Record<Step, string>> {
  return {
    1: copy.steps.choose,
    2: copy.steps.review,
    3: copy.steps.confirmed,
  };
}

/**
 * `parseAmount`'s rejection reasons, worded for THIS field.
 *
 * The parser returns a reason rather than a message precisely so that each field
 * can say what it means here: the same `not-positive` is "a goal has to be more
 * than nothing" on a campaign form and this on a pledge.
 */
function contributionMessage(
  reason: AmountRejection,
  minimum: string | null,
  copy: CheckoutCopy,
): string {
  switch (reason) {
    case 'empty':
      return minimum === null
        ? copy.errors.amountMissing
        : fillPlaceholders(copy.errors.amountMissingMinimum, { minimum });
    case 'comma':
      return copy.errors.amountComma;
    case 'not-a-number':
      return copy.errors.amountNotANumber;
    case 'too-many-decimals':
      return copy.errors.amountPrecision;
    case 'too-large':
      return copy.errors.amountTooLarge;
    case 'not-positive':
      return copy.errors.amountTooSmall;
  }
}

/** A quote refusal, worded for the control it belongs to. */
function refusalMessage(refusal: QuoteRefusal, copy: CheckoutCopy): string {
  switch (refusal.reason) {
    case 'contribution-below-price':
      return fillPlaceholders(copy.errors.belowRewardPrice, {
        price: formatMoney(refusal.price),
      });
    case 'destination-missing':
      return copy.errors.destinationMissing;
    case 'destination-unpriced':
      return fillPlaceholders(copy.errors.destinationUnpriced, {
        lines: refusal.lines.join(', '),
      });
    case 'nothing-pledged':
      return copy.errors.totalTooSmall;
  }
}

/**
 * What the confirmation says about the card, READ FROM THE PLEDGE.
 *
 * Both sentences used to be written here, and both were true — no card is
 * collected anywhere in this build and §9.2 moves no money at confirmation in
 * any case. Written here they are claims this screen makes about a service it
 * cannot see, and they stay on the screen until somebody remembers to change
 * them; #55 is exactly the change that would make the first one false. So the
 * response's `cardVerified` and `paymentMethodId` decide the wording, and the
 * day the service starts verifying cards the screen stops saying it does not.
 *
 * NEITHER SENTENCE MAY SOFTEN INTO "THANK YOU FOR YOUR PAYMENT". §9.2 collects
 * nothing until the campaign succeeds, whatever these two fields say, and a
 * confirmation that implied otherwise would have somebody budgeting for money
 * that has not left their account.
 */
function cardStatement(
  pledge: PledgeResponse,
  copy: CheckoutCopy,
): { readonly card: string; readonly method: string } {
  return {
    card: pledge.cardVerified
      ? copy.done.released
      : copy.done.noCard,
    method:
      pledge.paymentMethodId == null
        ? copy.done.noMethod
        : copy.done.methodKept,
  };
}

/** The failure banner, with whatever the recovery for this code happens to be. */
function FailureNotice({
  failure,
  alternatives,
  onChoose,
  onRetry,
  onReserveAgain,
  copy,
}: {
  failure: CheckoutFailure;
  alternatives: readonly PublicReward[];
  onChoose: (id: string) => void;
  onRetry: () => void;
  onReserveAgain: () => void;
  copy: CheckoutCopy;
}) {
  return (
    <InlineAlert variant="danger" title={failure.title}>
      <p>{failure.detail}</p>

      {/*
        §10.4's `meta.availableAlternatives`, resolved against the list already on
        screen. Ids are what the service sends and titles are what a backer can
        act on, so the ids that are not in this list — a secret tier, or one that
        went while the page was open — are dropped rather than rendered as a
        button whose label would have to be a UUID.
      */}
      {alternatives.length > 0 && (
        <div className="mt-3 flex flex-col gap-2">
          <p>{copy.stillAvailable}</p>
          <div className="flex flex-wrap gap-2">
            {alternatives.map((reward) => (
              <Pill key={reward.id} size="sm" variant="ghost" onClick={() => onChoose(reward.id)}>
                {reward.title} · {formatMoney(reward.price)}
              </Pill>
            ))}
          </div>
        </div>
      )}

      {/*
        `wait-and-retry` is offered as an ordinary "try again" and not as a
        second kind of button. Reaching this notice at all means the hook has
        already waited and re-sent the request as many times as it is willing
        to, so what is left for the backer is exactly what `retry` offers: send
        it once more, under the same key, which is safe.
      */}
      {(failure.recovery === 'retry' || failure.recovery === 'wait-and-retry') && (
        <div className="mt-3">
          <Pill size="sm" variant="ghost" onClick={onRetry}>
            {copy.tryAgain}
          </Pill>
        </div>
      )}

      {failure.recovery === 'redraft' && (
        <div className="mt-3">
          <Pill size="sm" variant="ghost" onClick={onReserveAgain}>
            {copy.reserveAgain}
          </Pill>
        </div>
      )}
    </InlineAlert>
  );
}

export interface CheckoutViewProps {
  projectId: string;
  /**
   * PL-15's secret tokens, read from the private link the route was reached by.
   *
   * A prop rather than a `useSearchParams` call: the route already has the query
   * string on the server, and reading it again here would force the whole page
   * into a `Suspense` boundary for a value that never changes while somebody is
   * on it.
   */
  secretTokens?: readonly string[];
  /**
   * Every word this screen and its five children draw, resolved on the server by
   * `app/[locale]/projects/[id]/back/page.tsx`. `lib/i18n/checkout-copy.ts` explains why it
   * arrives whole rather than through `useTranslations`, and why on this screen in particular
   * a missing string is a money question rather than a cosmetic one.
   */
  copy: CheckoutCopy;
}

export function CheckoutView({ projectId, secretTokens = [], copy }: CheckoutViewProps) {
  const checkout = useCheckout(projectId, secretTokens);
  const clock = useReservationClock(checkout.pledge?.reservationExpiresAt);

  /*
   * `reserving` is not a step of its own, and which step it belongs to depends
   * on where it was started from: reserving for the first time is still step
   * one, and reserving AGAIN after a hold expired is still step two. Without the
   * second reading the page would drop back to the form for the length of one
   * request and take the reader's focus with it.
   */
  const step: Step =
    checkout.phase === 'confirmed'
      ? 3
      : checkout.phase === 'reserved' ||
          checkout.phase === 'confirming' ||
          (checkout.phase === 'reserving' && checkout.pledge !== null)
        ? 2
        : 1;

  /*
   * Focus follows the step. Without this a keyboard or screen-reader user
   * presses "Reserve", the page replaces everything below the heading, and their
   * focus is left on a button that no longer exists — which drops them at the top
   * of the document with no announcement of what happened. The heading is not in
   * the tab order (`tabIndex={-1}`); it is only a place for focus to land.
   */
  const heading = useRef<HTMLHeadingElement>(null);
  const previousStep = useRef<Step>(step);
  useEffect(() => {
    if (previousStep.current === step) return;
    previousStep.current = step;
    heading.current?.focus();
  }, [step]);

  const catalogue = checkout.catalogue;
  const rewardTitle = checkout.reward?.title ?? null;

  const quote = checkout.quote;
  const refusal = quote !== null && !quote.ok ? quote.refusal : null;

  const options = useMemo(
    () => (checkout.selection === null ? [] : destinationOptions(checkout.selection)),
    [checkout.selection],
  );

  const regionNames = useMemo(() => {
    try {
      return new Intl.DisplayNames(['en'], { type: 'region' });
    } catch {
      return null;
    }
  }, []);

  const alternatives = useMemo(() => {
    const suggested = checkout.failure?.alternatives ?? [];
    if (suggested.length === 0 || catalogue === null) return [];
    return catalogue.rewards.filter(
      (reward) => suggested.includes(reward.id) && reward.remainingQuantity !== 0,
    );
  }, [checkout.failure, catalogue]);

  /*
   * THE AMOUNTS ON SCREEN, and the one line that decides which of the two is
   * shown: the server's, from the moment there is a server's. The client's
   * preview exists to make the figure move while somebody is choosing, and it
   * stops existing the instant a draft has been quoted — the two are never
   * merged, never compared, and never shown together.
   */
  const amounts =
    checkout.pledge !== null
      ? checkout.pledge.amounts
      : quote !== null && quote.ok
        ? toAmounts(quote.quote)
        : null;

  const statement = checkout.pledge === null ? null : cardStatement(checkout.pledge, copy);

  const stepName = stepNames(copy);

  const destinationLabel =
    checkout.destination === null ? null : countryName(checkout.destination, regionNames);

  const contributionError =
    !checkout.contributionShown
      ? null
      : !checkout.contribution.ok
        ? contributionMessage(
            checkout.contribution.reason,
            checkout.reward === null ? null : formatMoney(checkout.reward.price),
            copy,
          )
        : refusal !== null &&
            (refusal.reason === 'contribution-below-price' || refusal.reason === 'nothing-pledged')
          ? refusalMessage(refusal, copy)
          : null;

  const destinationError =
    refusal !== null && refusal.reason === 'destination-unpriced'
      ? refusalMessage(refusal, copy)
      : checkout.attempted && refusal !== null && refusal.reason === 'destination-missing'
        ? refusalMessage(refusal, copy)
        : null;

  return (
    <div className="mx-auto w-full max-w-[1100px] px-5 py-10 sm:px-6">
      <p className="text-xs font-medium tracking-[0.06em] text-white/40 uppercase">{copy.title}</p>
      {/*
        NO `outline-none` HERE. The global `:focus-visible` rule (docs/ui-kit.md
        §9.3) is the only focus ring in the system and suppressing it on the one
        element focus is moved to would take the ring away from precisely the
        reader it was moved for. The browser's own heuristic already withholds it
        after a pointer interaction, which is the behaviour wanted in both cases.
      */}
      <h1
        ref={heading}
        tabIndex={-1}
        className="mt-1 text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl"
      >
        {stepName[step]}
      </h1>

      {/* The step indicator is words as well as marks. A row of dots is colour
          and shape carrying meaning by itself, which §9.4 forbids outright. */}
      <nav aria-label={copy.progress} className="mt-4">
        <ol className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px] text-white/40">
          {([1, 2, 3] as const).map((index) => (
            <li key={index} className="flex items-center gap-2">
              {index > 1 && <span aria-hidden="true">·</span>}
              <span
                aria-current={index === step ? 'step' : undefined}
                className={index === step ? 'text-white' : undefined}
              >
                {index}. {stepName[index]}
              </span>
            </li>
          ))}
        </ol>
      </nav>

      {/*
        A polite region, permanent so that its text changes rather than the
        element appearing — an inserted live region is read as ordinary content
        instead of announced. It carries the step outcome and NOT the countdown;
        a clock in here would talk over everything on the page every second.
      */}
      <p role="status" aria-live="polite" className="sr-only">
        {checkout.phase === 'reserved'
          ? copy.reserved
          : checkout.phase === 'confirmed' && statement !== null
            ? `Your pledge is confirmed. ${statement.card}`
            : ''}
      </p>

      <div className="mt-8 flex flex-col gap-8 lg:flex-row">
        {/* A `div` and not a second `<main>`: the route already opened one, and
            a document with two of them has no main landmark at all. */}
        <div className="flex min-w-0 flex-1 flex-col gap-6">
          {checkout.failure !== null && (
            <FailureNotice
              copy={copy}
              failure={checkout.failure}
              alternatives={alternatives}
              onChoose={checkout.chooseReward}
              onRetry={checkout.retry}
              onReserveAgain={() => checkout.reserve({ fresh: true })}
            />
          )}

          {step === 1 && (
            <>
              {checkout.catalogueStatus === 'loading' && (
                <SkeletonGroup label={copy.loading}>
                  <div className="flex flex-col gap-3">
                    <Skeleton height="5rem" />
                    <Skeleton height="5rem" />
                    <Skeleton height="5rem" />
                  </div>
                </SkeletonGroup>
              )}

              {checkout.catalogueStatus === 'failed' && checkout.catalogueFailure !== null && (
                <InlineAlert variant="danger" title={checkout.catalogueFailure.title}>
                  <p>{checkout.catalogueFailure.detail}</p>
                  <div className="mt-3">
                    <Pill size="sm" variant="ghost" onClick={checkout.reloadCatalogue}>
                      {copy.tryAgain}
                    </Pill>
                  </div>
                </InlineAlert>
              )}

              {checkout.catalogueStatus === 'ready' && catalogue !== null && (
                <>
                  <RewardChoice
                    copy={copy.reward}
                    rewards={catalogue.rewards}
                    value={checkout.choice}
                    onChange={checkout.chooseReward}
                  />

                  {checkout.attempted && checkout.choice === null && (
                    <InlineAlert variant="warning" title={copy.reward.chooseOne}>
                      {copy.intro}
                    </InlineAlert>
                  )}

                  {checkout.choice !== null && (
                    <Field
                      label={checkout.choice === NO_REWARD ? 'How much would you like to give?' : copy.contribution.legend}
                      required
                      hint={
                        checkout.reward === null
                          ? copy.contribution.hint
                          : `This reward costs ${formatMoney(checkout.reward.price)}. Give more if you would like to; the extra is bonus support.`
                      }
                      error={contributionError}
                    >
                      <TextInput
                        // `inputMode` rather than `type="number"`: a number input
                        // accepts `1e5`, strips what it dislikes on paste, and
                        // hands back a value this application has to re-parse
                        // anyway. `parseAmount` is the one reader of an amount.
                        inputMode="decimal"
                        autoComplete="off"
                        value={checkout.contributionText}
                        onChange={(event) => checkout.setContributionText(event.currentTarget.value)}
                        trailing={<span className="text-[13px]">{catalogue.currency}</span>}
                      />
                    </Field>
                  )}

                  <AddonChoice
                    copy={copy.addons}
                    addons={catalogue.addons}
                    quantityOf={checkout.addonQuantity}
                    onChange={checkout.setAddonQuantity}
                  />

                  {checkout.needsDestination && (
                    <DestinationField
                      copy={copy.destination}
                      options={options}
                      value={checkout.destination}
                      onChange={checkout.setDestination}
                      error={destinationError}
                    />
                  )}

                  <Checkbox
                    checked={checkout.isAnonymous}
                    onChange={(event) => checkout.setAnonymous(event.currentTarget.checked)}
                    label={copy.anonymous.label}
                    /*
                      PL-12 says what it does and does not overstate it. Anonymous
                      means hidden from the campaign's public backer list. The
                      creator still sees who backed them — they have to, in order
                      to post what was promised — and the pledge is still on the
                      account. Saying "anonymous" and leaving a backer to assume
                      the creator cannot see them would be the interface telling
                      a lie somebody might rely on.
                    */
                    description={copy.anonymous.hint}
                  />
                </>
              )}
            </>
          )}

          {step === 2 && checkout.pledge !== null && (
            <>
              {clock.expired ? (
                <InlineAlert variant="warning" title={copy.done.expired}>
                  <p>
                    {copy.expired}
                  </p>
                  <div className="mt-3">
                    <Pill size="sm" variant="ghost" onClick={() => checkout.reserve({ fresh: true })}>
                      {copy.reserveAgain}
                    </Pill>
                  </div>
                </InlineAlert>
              ) : (
                <p className="text-sm text-white/64">
                  Your reward is held for{' '}
                  {/* No transition on this number. docs/motion-system.md §6:
                      the countdown must not animate, it simply changes. */}
                  <strong className="tabular-nums text-white">{clock.label}</strong>. After that the
                  stock goes back to the campaign and you would reserve it again.
                </p>
              )}

              <section aria-labelledby="checkout-selection" className="flex flex-col gap-2">
                <h2 id="checkout-selection" className="text-sm font-medium text-white">
                  {copy.review.heading}
                </h2>
                <dl className="flex flex-col gap-1 text-sm text-white/64">
                  <div className="flex gap-2">
                    <dt>{copy.review.reward}</dt>
                    <dd className="text-white">{rewardTitle ?? copy.review.noReward}</dd>
                  </div>
                  {checkout.pledge.addons.map((addon) => {
                    const named = catalogue?.addons.find((entry) => entry.id === addon.rewardTierId);
                    return (
                      <div key={addon.rewardTierId} className="flex gap-2">
                        <dt>{copy.review.addon}</dt>
                        <dd className="text-white">
                          {addon.quantity} × {named?.title ?? 'Add-on'}
                        </dd>
                      </div>
                    );
                  })}
                  {checkout.pledge.shippingCountry != null && (
                    <div className="flex gap-2">
                      <dt>{copy.review.deliveredTo}</dt>
                      <dd className="text-white">
                        {countryName(checkout.pledge.shippingCountry, regionNames)}
                      </dd>
                    </div>
                  )}
                  <div className="flex gap-2">
                    <dt>{copy.review.listedAs}</dt>
                    <dd className="text-white">
                      {checkout.pledge.isAnonymous ? copy.anonymous.shown : copy.anonymous.yourName}
                    </dd>
                  </div>
                </dl>
              </section>

              <PaymentStep copy={copy.payment} />
            </>
          )}

          {step === 3 && checkout.pledge !== null && statement !== null && (
            <>
              <InlineAlert variant="success" title={copy.done.announced}>
                <p>
                  {copy.done.heading}
                </p>
              </InlineAlert>

              {/*
                THE SENTENCE THAT MUST NOT BE SOFTENED. §9.2: nothing is collected
                until the campaign succeeds. A confirmation screen that says
                "thank you for your payment" would have somebody budgeting for
                money that has not left their account, and would have them looking
                for a refund that has nothing to refund.

                Both halves of it are READ FROM THE PLEDGE — see `cardStatement`.
              */}
              <section aria-labelledby="checkout-what-happens" className="flex flex-col gap-2">
                <h2 id="checkout-what-happens" className="text-sm font-medium text-white">
                  {copy.done.next}
                </h2>
                <p className="text-sm text-white/64">
                  <strong className="text-white">{statement.card}</strong> {statement.method} If it
                  does, you will be told before anything is collected. If it does not, nothing
                  happens at all.
                </p>
              </section>

              <section aria-labelledby="checkout-confirmed-selection" className="flex flex-col gap-2">
                <h2 id="checkout-confirmed-selection" className="text-sm font-medium text-white">
                  {copy.done.backed}
                </h2>
                <dl className="flex flex-col gap-1 text-sm text-white/64">
                  <div className="flex gap-2">
                    <dt>{copy.review.reward}</dt>
                    <dd className="text-white">{rewardTitle ?? copy.review.noReward}</dd>
                  </div>
                  {checkout.pledge.shippingCountry != null && (
                    <div className="flex gap-2">
                      <dt>{copy.review.deliveredTo}</dt>
                      <dd className="text-white">
                        {countryName(checkout.pledge.shippingCountry, regionNames)}
                      </dd>
                    </div>
                  )}
                  <div className="flex gap-2">
                    <dt>{copy.done.reference}</dt>
                    <dd className="text-white">{checkout.pledge.id}</dd>
                  </div>
                </dl>
              </section>
            </>
          )}
        </div>

        <aside
          aria-label={copy.summary.label}
          className="w-full shrink-0 lg:sticky lg:top-6 lg:w-[360px] lg:self-start"
        >
          <PledgeSummary
            copy={copy.summary}
            amounts={amounts}
            source={checkout.pledge === null ? 'preview' : 'quoted'}
            rewardTitle={rewardTitle}
            destination={destinationLabel}
            /*
             * A pointer back to the control, not a second copy of its message.
             * Every refusal is already worded beside the field it belongs to,
             * and printing the same sentence twice on one screen makes a reader
             * hunt for the difference between them.
             */
            unavailable={
              checkout.pledge === null && refusal !== null
                ? copy.summary.pending
                : null
            }
          >
            {step === 1 && (
              <Pill
                fullWidth
                /*
                 * NOT LIME, AND NOT `primary` EITHER. §8.5 gives checkout exactly
                 * one lime element and it is the confirm button on the next step
                 * — "continue" is not urgent. `primary` is `--white-surface`,
                 * which is invisible on this panel, and `outline` is a white
                 * hairline on white. `ghost` is the one variant that reads here,
                 * and a dark fill on a white panel is what §8.5's sketch shows a
                 * secondary action to be.
                 */
                variant="ghost"
                onClick={() => checkout.reserve()}
                disabled={checkout.phase === 'reserving' || checkout.catalogueStatus !== 'ready'}
              >
                {checkout.phase === 'reserving' ? 'Reserving…' : copy.review.reserve}
              </Pill>
            )}

            {step === 2 && (
              <>
                {/* The one lime element on the screen (§8.5). */}
                <Pill
                  fullWidth
                  variant="accent"
                  onClick={checkout.confirm}
                  disabled={checkout.phase === 'confirming' || clock.expired}
                >
                  {checkout.phase === 'confirming' ? copy.review.confirming : copy.review.confirm}
                </Pill>
                <p className="text-[13px] text-on-white/64">
                  {copy.review.notCharged}
                </p>
                <Pill fullWidth variant="ghost" onClick={checkout.startOver}>
                  {copy.review.change}
                </Pill>
              </>
            )}

            {step === 3 && (
              <p className="text-[13px] text-on-white/64">
                {copy.done.keepReference}
              </p>
            )}
          </PledgeSummary>
        </aside>
      </div>
    </div>
  );
}
