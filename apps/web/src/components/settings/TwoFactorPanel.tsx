'use client';

import { useLayoutEffect, useRef, useState, type FormEvent } from 'react';
import { Checkbox, Field, InlineAlert, Pill, TextInput } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import {
  confirmTwoFactorEnrolment,
  disableTwoFactor,
  startTwoFactorEnrolment,
  type TwoFactorEnrolment,
} from '../../lib/auth/twoFactor';

/**
 * §4.1's A-07 — enrolling in two-factor, seeing the recovery codes once, and switching it
 * off. Issue #278.
 *
 * <h2>Enrolling is not enabling, and the screen is two steps because of it</h2>
 *
 * `TwoFactorEnrolmentService` states the rule and the reason: starting an enrolment writes a
 * secret and nothing else, and two-factor stays off until a current code has been entered.
 * "Anything else locks out the user whose phone dies between scanning the code and typing one,
 * and a lockout on a funding platform means somebody cannot reach their money." So this panel
 * cannot collapse the two calls into one button — the second step is the thing that makes the
 * first safe to abandon.
 *
 * <h2>The recovery codes are shown once and the screen says so before it moves on</h2>
 *
 * `POST /2fa/confirm` is "the only response that will ever contain them" — what is stored is a
 * hash. There is no re-issue endpoint, so the only way to see a set again is to disable and
 * enrol afresh. The step that shows them therefore has no automatic navigation and one
 * deliberate acknowledgement, and it says what happens if they are lost.
 *
 * <h2>This screen cannot read whether two-factor is already on, and does not pretend to</h2>
 *
 * `GET /v1/me` returns six fields and none of them is `twoFactorEnabled` — there is no read
 * anywhere in the service that answers it. Two options were available and one of them is a
 * lie: guessing from the absence of information, or offering both paths and letting the
 * service answer. This does the second. `POST /2fa/enable` refuses an already-confirmed
 * enrolment with a sentence written for the account's owner — "Two-factor authentication is
 * already enabled." — and that refusal is what switches this panel to the off-path, with the
 * service's own words above it.
 *
 * The honest fix is a field on `GET /v1/me`, which belongs to whoever owns §17 rather than to
 * an epic scoped to the web client. `apps/web/README.md` records the gap.
 *
 * <h2>There is no QR code, and that is a dependency decision rather than an oversight</h2>
 *
 * Drawing one means shipping a QR encoder on a settings route. What the service returns is an
 * `otpauth://` URI, which is the same payload a QR would carry: on a phone it opens the
 * authenticator directly, and on a desktop the base32 secret beside it is what every
 * authenticator's "enter a code manually" accepts. Both are offered, and the secret is
 * selectable in one gesture rather than being a wall of characters to transcribe.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 — "authentication, account settings: none". The steps replace one
 * another outright.
 */

type Step =
  | { readonly kind: 'idle' }
  | { readonly kind: 'password' }
  | { readonly kind: 'scan'; readonly enrolment: TwoFactorEnrolment }
  | { readonly kind: 'codes'; readonly codes: readonly string[] }
  | { readonly kind: 'disable' };

function messageFor(cause: unknown): string {
  if (cause instanceof ApiError) {
    return (
      cause.problem?.detail ?? cause.problem?.title ?? 'The service refused the request. Try again.'
    );
  }
  return 'The service could not be reached. Check your connection and try again.';
}

/** The service's own sentence for an account that is already enrolled. */
const ALREADY_ENABLED = 'Two-factor authentication is already enabled.';

export function TwoFactorPanel() {
  const [step, setStep] = useState<Step>({ kind: 'idle' });
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [recoveryCode, setRecoveryCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);

  /*
   * Focus is moved to the step's heading on every transition. Without it a keyboard reader
   * presses a button and the page silently becomes a different form under a focus ring that
   * is now on nothing — the same reason `SiteShell`'s `<main>` takes `tabIndex={-1}`.
   */
  const headingRef = useRef<HTMLHeadingElement>(null);

  /*
   * THE MOVE HAPPENS IN A LAYOUT EFFECT AND NOT IN A `requestAnimationFrame`, AND #322 IS
   * WHY. A frame callback is scheduled for the next paint, which is after the browser has
   * already delivered whatever the reader typed in the meantime. Someone who presses "Turn
   * it off" and starts typing their password immediately gets the first character into the
   * field and the rest into the heading, because focus is taken out from under them a frame
   * later. On a loaded machine that frame is long enough to swallow a whole password, which
   * is how the flake in #322 read: `'correct horse'` arriving as `'c'`, the six-digit code
   * beside it intact because by then the move had already happened.
   *
   * A layout effect runs synchronously after the commit that rendered the new step and
   * before the browser paints or dispatches another input event, so there is no window to
   * lose a keystroke in. `moved` gates it: the effect must not fire on mount, where nobody
   * asked for focus and stealing it would drag the page down to this panel on load.
   */
  const moved = useRef(false);

  useLayoutEffect(() => {
    if (!moved.current) return;
    moved.current = false;
    headingRef.current?.focus();
  }, [step]);

  function go(next: Step): void {
    setStep(next);
    setError(null);
    setCode('');
    setRecoveryCode('');
    moved.current = true;
  }

  async function begin(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (busy) return;

    setBusy(true);
    setError(null);
    try {
      const enrolment = await startTwoFactorEnrolment(password);
      setPassword('');
      go({ kind: 'scan', enrolment });
    } catch (cause) {
      const detail = messageFor(cause);
      if (detail === ALREADY_ENABLED) {
        /*
         * Not an error the reader can act on where they are — it is the answer to the
         * question this panel could not ask. The password they just proved is the same one
         * the off-path needs, so they are moved there rather than sent back to the start.
         */
        setNotice(detail);
        go({ kind: 'disable' });
        return;
      }
      setError(detail);
    } finally {
      setBusy(false);
    }
  }

  async function confirm(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (busy || step.kind !== 'scan') return;

    setBusy(true);
    setError(null);
    try {
      const codes = await confirmTwoFactorEnrolment(code.trim());
      setAcknowledged(false);
      go({ kind: 'codes', codes });
    } catch (cause) {
      setError(messageFor(cause));
    } finally {
      setBusy(false);
    }
  }

  async function turnOff(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (busy) return;

    const typedRecovery = recoveryCode.trim();
    const typedCode = code.trim();
    if (password === '' || (typedCode === '' && typedRecovery === '')) return;

    setBusy(true);
    setError(null);
    try {
      await disableTwoFactor(
        password,
        typedRecovery === ''
          ? { kind: 'code', code: typedCode }
          : { kind: 'recovery-code', recoveryCode: typedRecovery },
      );
      setPassword('');
      setNotice('Two-factor authentication is off. Your password alone signs you in again.');
      go({ kind: 'idle' });
    } catch (cause) {
      setError(messageFor(cause));
    } finally {
      setBusy(false);
    }
  }

  const heading = (
    <h2
      ref={headingRef}
      tabIndex={-1}
      className="text-lg font-medium tracking-[-0.02em] text-white focus:outline-none"
    >
      {step.kind === 'scan'
        ? 'Add IdeaNest to your authenticator'
        : step.kind === 'codes'
          ? 'Save your recovery codes'
          : step.kind === 'disable'
            ? 'Turn two-factor authentication off'
            : step.kind === 'password'
              ? 'Confirm your password'
              : 'Two-factor authentication'}
    </h2>
  );

  return (
    <section className="rounded-2xl border border-white/8 bg-surface-2 p-6 sm:p-8">
      {heading}

      {notice !== null && step.kind !== 'codes' && (
        <div className="mt-5">
          <InlineAlert variant="info" title="From the service" onDismiss={() => setNotice(null)}>
            <p>{notice}</p>
          </InlineAlert>
        </div>
      )}

      {error !== null && (
        <div className="mt-5">
          <InlineAlert variant="danger" title="That did not work">
            <p>{error}</p>
          </InlineAlert>
        </div>
      )}

      {step.kind === 'idle' && (
        <div className="mt-4 flex flex-col gap-6">
          <p className="max-w-[62ch] text-[15px] leading-relaxed text-white/64">
            A code from your phone, on top of your password. §4.1 makes it{' '}
            <strong className="font-medium text-white">required before a payout</strong>, so a
            creator will be asked for one sooner or later — switching it on now is the cheaper
            moment.
          </p>
          <div className="flex flex-wrap gap-3">
            <Pill type="button" onClick={() => go({ kind: 'password' })}>
              Set it up
            </Pill>
            <Pill type="button" variant="ghost" onClick={() => go({ kind: 'disable' })}>
              Turn it off
            </Pill>
          </div>
          <p className="max-w-[62ch] text-sm text-white/40">
            Both are offered because this screen cannot read which one applies — the service
            publishes no field saying whether your account is enrolled. Whichever you choose, it
            answers honestly.
          </p>
        </div>
      )}

      {step.kind === 'password' && (
        <form onSubmit={begin} noValidate className="mt-4 flex max-w-[26rem] flex-col gap-5">
          <p className="text-[15px] leading-relaxed text-white/64">
            Turning a security control on costs your password, so that a stolen sign-in cannot
            bolt a second factor onto your account.
          </p>
          <Field label="Current password" required>
            <TextInput
              type="password"
              name="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </Field>
          <div className="flex flex-wrap gap-3">
            <Pill type="submit" disabled={busy}>
              {busy ? 'Checking' : 'Continue'}
            </Pill>
            <Pill type="button" variant="ghost" onClick={() => go({ kind: 'idle' })}>
              Cancel
            </Pill>
          </div>
        </form>
      )}

      {step.kind === 'scan' && (
        <form onSubmit={confirm} noValidate className="mt-4 flex flex-col gap-6">
          <p className="max-w-[62ch] text-[15px] leading-relaxed text-white/64">
            Two-factor is <strong className="font-medium text-white">not on yet</strong>. Add this
            to your authenticator, then enter the code it shows — that is what switches it on.
          </p>

          <div className="flex flex-col gap-4 rounded-xl border border-white/8 bg-surface-1 p-5">
            <div>
              <p className="text-sm text-white/64">On this device</p>
              <a
                href={step.enrolment.otpauthUri}
                className="mt-1 inline-block rounded-sm text-[15px] text-white underline underline-offset-4 hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
              >
                Open in your authenticator app
              </a>
            </div>
            <div>
              <p className="text-sm text-white/64">Or enter this key by hand</p>
              {/*
                `break-all` on a monospace run: a base32 secret has no spaces to wrap at, and
                without it the card widens until the layout scrolls sideways.
              */}
              <code className="mt-1 block font-mono text-[15px] break-all text-white select-all">
                {step.enrolment.secret}
              </code>
              <p className="mt-2 text-sm text-white/40">
                {step.enrolment.digits} digits, every {step.enrolment.periodSeconds} seconds,{' '}
                {step.enrolment.algorithm}.
              </p>
            </div>
          </div>

          <Field label="Code from your authenticator" required>
            <TextInput
              name="code"
              autoComplete="one-time-code"
              inputMode="numeric"
              maxLength={16}
              value={code}
              onChange={(event) => setCode(event.target.value)}
              placeholder="000000"
            />
          </Field>

          <div className="flex flex-wrap gap-3">
            <Pill type="submit" disabled={busy}>
              {busy ? 'Confirming' : 'Switch it on'}
            </Pill>
            <Pill type="button" variant="ghost" onClick={() => go({ kind: 'idle' })}>
              Cancel
            </Pill>
          </div>
        </form>
      )}

      {step.kind === 'codes' && (
        <div className="mt-4 flex flex-col gap-6">
          <InlineAlert variant="warning" title="This is the only time these are shown">
            <p>
              We store a hash of them, so they cannot be shown again — only replaced by
              enrolling afresh. Each one works once, and any of them signs you in when your
              authenticator is out of reach.
            </p>
          </InlineAlert>

          <ul className="grid list-none grid-cols-2 gap-2 rounded-xl border border-white/8 bg-surface-1 p-5 font-mono text-[15px] text-white select-all sm:grid-cols-3">
            {step.codes.map((recovery) => (
              <li key={recovery}>{recovery}</li>
            ))}
          </ul>

          {/*
            THE ACKNOWLEDGEMENT GATES THE ONLY WAY OFF THIS STEP, deliberately. There is no
            re-issue endpoint, so somebody who clicks past this screen has lost the codes for
            good — a confirmation that costs one click is cheap against that.
          */}
          <Checkbox
            checked={acknowledged}
            onChange={(event) => setAcknowledged(event.target.checked)}
            label="I have saved these somewhere I can reach without my phone."
          />

          <div>
            <Pill
              type="button"
              disabled={!acknowledged}
              onClick={() => {
                setNotice('Two-factor authentication is on. You will be asked for a code when you sign in.');
                go({ kind: 'idle' });
              }}
            >
              Done
            </Pill>
          </div>
        </div>
      )}

      {step.kind === 'disable' && (
        <form onSubmit={turnOff} noValidate className="mt-4 flex max-w-[26rem] flex-col gap-5">
          <p className="text-[15px] leading-relaxed text-white/64">
            Your password <em>and</em> a code. Without the code the whole feature would be worth
            exactly one password, which is the thing it was added to stop being enough.
          </p>

          <Field label="Current password" required>
            <TextInput
              type="password"
              name="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </Field>

          <Field label="Code from your authenticator">
            <TextInput
              name="code"
              autoComplete="one-time-code"
              inputMode="numeric"
              maxLength={16}
              value={code}
              onChange={(event) => setCode(event.target.value)}
              placeholder="000000"
            />
          </Field>

          <Field
            label="Or a recovery code"
            hint="For the person whose phone is the reason they are here. Each one works once."
          >
            <TextInput
              name="recoveryCode"
              autoComplete="off"
              spellCheck={false}
              maxLength={40}
              value={recoveryCode}
              onChange={(event) => setRecoveryCode(event.target.value)}
            />
          </Field>

          <div className="flex flex-wrap gap-3">
            <Pill type="submit" variant="danger" disabled={busy}>
              {busy ? 'Turning it off' : 'Turn it off'}
            </Pill>
            <Pill type="button" variant="ghost" onClick={() => go({ kind: 'idle' })}>
              Cancel
            </Pill>
          </div>
        </form>
      )}
    </section>
  );
}
