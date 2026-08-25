'use client';

import { useState, type FormEvent } from 'react';
import { Link } from '../../i18n/navigation';
import { MailQuestion } from 'lucide-react';
import { Field, Pill, TextInput } from '@ideanest/ui';
import { describeAuthFailure, fieldErrorsOf, type AuthFailure } from '../../lib/auth/failures';
import { RESET_LINK_LIFETIME, requestPasswordReset } from '../../lib/auth/passwordReset';
import { AuthPageHeader } from './AuthPageHeader';
import { FormErrorSummary } from './FormErrorSummary';

/**
 * §4.1's A-06, first half — asking for a reset link. Issue #271.
 *
 * <h2>The screen after submitting is the whole design, and it is written to be true</h2>
 *
 * `POST /v1/auth/forgot-password` answers **202 whether or not the address has an account**,
 * and `PasswordResetService` gives the reason: an endpoint that answered "no such account" is
 * an enumeration oracle producing exactly the list somebody wants before writing a phishing
 * email — which people on a breach list have accounts here.
 *
 * **A client can give that away without the endpoint saying a word.** "Check your inbox — we
 * have sent you a link" is a claim about an account, and it is a claim this application was
 * deliberately not told enough to make. So the state below says *if*: the request was accepted,
 * and a link is on its way if the address has an account. That sentence is exactly as true for
 * an address with no account as for one with, which is the property the 202 was chosen for.
 *
 * The consequence to accept honestly is that somebody who mistyped their address gets a
 * reassuring screen and no email. That is why the address is echoed back — a typo is the most
 * common reason nothing arrives, and it is the reader's own address rather than anything the
 * service disclosed — and why the way back to this form stays on the screen.
 *
 * <h2>The lifetime is said here, before anybody is waiting on it</h2>
 *
 * The link works for one hour and once. Saying so on the confirmation, rather than only in the
 * refusal an hour later, is the difference between a constraint and a surprise: somebody who
 * reads "one hour" goes and looks now, and somebody who does not comes back tomorrow to a page
 * that tells them off. `RESET_LINK_LIFETIME` is the single source of the phrase, so this screen
 * and `/reset-password/confirm` cannot disagree about it.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5's authentication row. The confirmation replaces the form outright.
 */
export function PasswordResetRequestForm() {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<AuthFailure | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({});
  const [askedFor, setAskedFor] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (submitting) return;

    setSubmitting(true);
    setFailure(null);
    setFieldErrors({});

    const address = email.trim();

    try {
      await requestPasswordReset(address);
      setAskedFor(address);
    } catch (cause) {
      setFailure(describeAuthFailure(cause));
      setFieldErrors(fieldErrorsOf(cause));
    } finally {
      setSubmitting(false);
    }
  }

  if (askedFor !== null) {
    return (
      <div>
        <AuthPageHeader title="Check your email">
          {/*
            "IF THAT ADDRESS HAS AN ACCOUNT" IS NOT HEDGING — it is the only sentence this
            screen is entitled to write. See the component comment.
          */}
          If <span className="text-white">{askedFor}</span> has an IdeaNest account, a link to
          set a new password is on its way to it.
        </AuthPageHeader>

        <div className="flex items-start gap-3 rounded-lg border border-white/8 bg-surface-2 p-5 text-[15px] leading-relaxed text-white/64">
          <MailQuestion aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-white/40" />
          <div>
            <p>
              The link works for {RESET_LINK_LIFETIME} and can be used once. Asking again
              replaces it, so use the most recent message.
            </p>
            <p className="mt-3">
              Nothing arrived? Check the address for a typo, then{' '}
              <button
                type="button"
                onClick={() => setAskedFor(null)}
                className="rounded-sm text-white underline underline-offset-4 hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
              >
                try another address
              </button>
              . We cannot tell you whether an account exists, so this screen looks the same
              either way.
            </p>
          </div>
        </div>

        <p className="mt-8 text-center text-sm text-white/64">
          <Link
            href="/sign-in"
            className="rounded-sm text-white underline underline-offset-4 hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            Back to sign in
          </Link>
        </p>
      </div>
    );
  }

  return (
    <>
      <AuthPageHeader title="Reset your password">
        Give us the address you sign in with and we will send a link that sets a new password.
        You will not need the old one.
      </AuthPageHeader>

      {/*
        `noValidate` for `SignInForm`'s reason: §9.2 requires the message to be text beside the
        field and wired to it, and the browser's own bubble is neither — it disappears on the
        next keystroke and is invisible to a screen reader that is not focused on the control.
        `type="email"` stays, because it still chooses the right keyboard on a phone.
      */}
      <form onSubmit={submit} noValidate className="flex flex-col gap-5">
        <FormErrorSummary failure={failure} />

        <Field label="Email address" required error={fieldErrors['email']}>
          <TextInput
            type="email"
            name="email"
            autoComplete="email"
            inputMode="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="you@example.com"
          />
        </Field>

        {/*
          THE CONTROL STAYS ON EVERY REFUSAL HERE, including the rate limit, and there is no
          `retryable` branch to write: none of this endpoint's refusals is a suspension. A 429
          says how long is left in `detail` and the same request works after it.
        */}
        <Pill type="submit" fullWidth size="lg" disabled={submitting}>
          {submitting ? 'Sending the link' : 'Send the link'}
        </Pill>

        <p className="text-center text-sm text-white/64">
          Remembered it?{' '}
          <Link
            href="/sign-in"
            className="rounded-sm text-white underline underline-offset-4 hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            Sign in
          </Link>
        </p>
      </form>
    </>
  );
}
