'use client';

import { InlineAlert } from '@ideanest/ui';
import type { CheckoutCopy } from '../../lib/i18n/checkout-copy';

/**
 * PL-07 and PL-08 — and this is where this issue stops.
 *
 * <h2>There is no card form here, and adding one would be a defect</h2>
 *
 * Card entry and 3-D Secure are #55, and #55 is blocked on #60, which carries
 * `status: needs-decision` because no payment provider has been chosen. CLAUDE.md
 * §5 is explicit about what that means: "Do not implement around them — the
 * decision changes the design." There is no provider, so there is no hosted
 * field, no SDK, no tokenisation endpoint and no 3-D Secure flow to call.
 *
 * A card input that posted to nothing would be worse than this empty space, for
 * two reasons that outlive the placeholder:
 *
 *   - §17.2 targets SAQ A, which is only available while card data never touches
 *     our servers. The moment a `<input name="card">` exists in this application,
 *     the scope of that assessment includes it — including the browser
 *     autofilling a real number into it
 *   - a field that looks like it takes a card teaches somebody that this is where
 *     a card goes on this site. That lesson is what a phishing page relies on
 *
 * <h2>What confirmation does today</h2>
 *
 * `POST /v1/pledges/{id}/confirm` performs the state transition and commits the
 * reserved stock; the provider call is a named seam that is not yet implemented,
 * and `paymentMethodId` is sent as null. §9.2 is in any case explicit that NO
 * MONEY MOVES AT CONFIRMATION even once the provider exists — phase 1 is a
 * verification authorisation that is immediately voided, and collection is phase
 * 2, at campaign close. So the pledge below is a commitment and not a payment,
 * and the interface says exactly that rather than implying a charge that has not
 * happened and will not happen for weeks.
 */
export interface PaymentStepProps {
  /**
   * The words this control draws, resolved on the server and handed down by `CheckoutView`.
   * `lib/i18n/checkout-copy.ts` explains why the checkout's copy travels as a prop.
   */
  copy: CheckoutCopy['payment'];
}

export function PaymentStep({ copy }: PaymentStepProps) {
  return (
    <section aria-labelledby="checkout-payment" className="flex flex-col gap-3">
      <h3 id="checkout-payment" className="text-sm font-medium text-white">
        {copy.heading}
      </h3>

      {/*
        `info`, not `warning`. Nothing is wrong and nothing needs the backer's
        attention interrupted — this is a statement about what this build does,
        and docs/ui-kit.md §7.15 keeps `role="alert"` for the two variants that
        genuinely need to interrupt.
      */}
      <InlineAlert variant="info" title={copy.none}>
        <p>
          {copy.body}
        </p>
        <p className="mt-2">
          {copy.later}
        </p>
      </InlineAlert>
    </section>
  );
}
