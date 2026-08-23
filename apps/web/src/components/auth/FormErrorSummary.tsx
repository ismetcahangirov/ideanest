'use client';

import { useEffect, useRef } from 'react';
import { InlineAlert } from '@ideanest/ui';
import type { AuthFailure } from '../../lib/auth/failures';

/**
 * The refusal at the top of a credential form, and the thing the keyboard lands on.
 *
 * <h2>Why a component rather than an `InlineAlert` at each call site</h2>
 *
 * `SignInForm` and `RegisterForm` render the alert inline and stop there, which is correct for
 * them: both are one screen with one control, and a reader who presses Sign in has not moved
 * far from the message that appears above it. The five screens #271 and #277 add are longer —
 * three fields, a warning paragraph, and in two cases a second form below — and on those the
 * same treatment leaves somebody who submitted by keyboard with focus on a button near the
 * bottom of a page whose only change happened at the top. They are told (the alert asserts)
 * and they still have to find it.
 *
 * So the summary is focusable and takes focus when a refusal arrives. It is `tabIndex={-1}`
 * rather than `0`: it is a destination, not a stop on the tab order, and adding it to the
 * order would put an unlabelled paragraph between the last field and the submit button on
 * every subsequent pass. `SiteShell`'s `<main>` and `TwoFactorPanel`'s step heading are
 * focusable on exactly the same terms.
 *
 * <h2>It is announced and it is focused, and that is deliberate rather than double</h2>
 *
 * `InlineAlert`'s `danger` variant carries `role="alert"`, so inserting it interrupts whatever
 * a screen reader is saying. Moving focus into it a moment later re-reads it. The pairing is
 * the established error-summary pattern, and the alternative is worse in both directions:
 * announcement without focus tells somebody a thing they then have to hunt for, and focus
 * without announcement is silent for anybody whose reading position was elsewhere.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 gives authentication and account settings a budget of "none — 150ms
 * colour on controls", and states the reason for this component specifically: "an animated
 * error is one that arrives after it was needed".
 */
export interface FormErrorSummaryProps {
  /** The refusal, or `null` when there is not one. Renders nothing for `null`. */
  readonly failure: AuthFailure | null;
}

export function FormErrorSummary({ failure }: FormErrorSummaryProps) {
  const container = useRef<HTMLDivElement>(null);

  /*
   * Keyed on the object rather than on its text. Two submissions refused for the same reason
   * produce two different `AuthFailure` values, and a reader who fixed the wrong field and
   * pressed the button again has to be sent back to the message a second time — an effect that
   * compared strings would run once and then go quiet exactly when it was needed most.
   */
  useEffect(() => {
    if (failure === null) return;
    container.current?.focus();
  }, [failure]);

  if (failure === null) return null;

  return (
    <div ref={container} tabIndex={-1} className="focus:outline-none">
      <InlineAlert variant="danger" title={failure.title}>
        <p>{failure.detail}</p>
      </InlineAlert>
    </div>
  );
}
