'use client';

import { useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { Field, InlineAlert, Pill, TextInput } from '@ideanest/ui';
import { deviceLabelOf, signIn } from '../../lib/auth/api';
import { describeAuthFailure, fieldErrorsOf, type AuthFailure } from '../../lib/auth/failures';
import { DEFAULT_SIGNED_IN_PATH, RETURN_TO_PARAM, safeReturnPath } from '../../lib/auth/redirect';
import { useSession } from '../session/SessionProvider';

/**
 * §4.1's A-03 — email and password sign-in. Issue #268.
 *
 * <h2>The two refusals the issue names, and why they are not one message</h2>
 *
 * **`ACCOUNT_SUSPENDED` is a 403 and must not be rendered as a failed sign-in.** The password
 * was right. `AuthExceptionHandler` says 403 rather than 401 precisely so that a client stops
 * offering to try again — a 401 would put somebody in a loop with a password that works. So
 * this form hides the submit button on that one refusal and shows what actually helps, which
 * is that support is the way through.
 *
 * **The rate limit is a 429 with a `Retry-After`,** and §17.3 sets it at five attempts per
 * fifteen minutes. Saying how long is left is the difference between waiting and pressing the
 * button until the window resets. `describeAuthFailure` owns both branches, so the register
 * page cannot come to a different conclusion about the same status.
 *
 * Everything else is the service's own sentence, verbatim (§10.4). Nothing here writes "wrong
 * email or password": the endpoint deliberately does not say which, and inventing a message
 * would be inventing a claim about which half was wrong.
 *
 * <h2>Where it goes afterwards</h2>
 *
 * `?next=` when there is one, reduced to a path on this origin by `safeReturnPath` — an open
 * redirect on a sign-in page is the classic phishing primitive, and that module lists every
 * shape it refuses. `replace`, not `push`: leaving the sign-in form in the history means Back
 * returns a signed-in reader to a form they have already completed.
 *
 * The session is re-read before navigating, so the header shows the reader's name on the page
 * they land on rather than a frame later.
 *
 * <h2>Two-factor</h2>
 *
 * `POST /v1/auth/login` answers 200 with a challenge instead of tokens when the account has a
 * second factor confirmed, and **the challenge screen is #272 and is not built.** This form
 * says so plainly rather than failing silently or, worse, reporting a wrong password. It is
 * reachable today only by an account that enrolled through another client, because the web
 * enrolment screen (#278) does not exist either — which is why #272 was not folded into this
 * pull request.
 *
 * <h2>Motion</h2>
 *
 * None. docs/motion-system.md §5: authentication gets "150ms colour on controls", and an
 * error that animates in is one that arrives after it was needed.
 */

export function SignInForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { refresh } = useSession();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<AuthFailure | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({});
  const [twoFactor, setTwoFactor] = useState(false);

  const returnTo = safeReturnPath(searchParams.get(RETURN_TO_PARAM)) ?? DEFAULT_SIGNED_IN_PATH;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;

    setSubmitting(true);
    setFailure(null);
    setFieldErrors({});
    setTwoFactor(false);

    try {
      const outcome = await signIn({
        email: email.trim(),
        password,
        ...(deviceLabel() === undefined ? {} : { deviceLabel: deviceLabel() as string }),
      });

      if (outcome.kind === 'two-factor-required') {
        setTwoFactor(true);
        return;
      }

      /*
       * The session is read before the navigation rather than after it. The shell reads its
       * state from the provider, and navigating first would land the reader on a page whose
       * header still says Sign in — briefly, and exactly at the moment they are checking
       * whether it worked.
       */
      await refresh();
      router.replace(returnTo);
    } catch (cause) {
      setFailure(describeAuthFailure(cause));
      setFieldErrors(fieldErrorsOf(cause));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-5">
      {/*
        `noValidate` because the browser's own bubble is not this form's error treatment:
        §9.2 requires the message to be text beside the field, wired to it, and a native
        bubble is neither — it disappears on the next keystroke and is invisible to a screen
        reader that is not focused on the control. The `type` attributes stay, because they
        still choose the right keyboard on a phone.
      */}

      {failure !== null && (
        <InlineAlert variant="danger" title={failure.title}>
          <p>{failure.detail}</p>
        </InlineAlert>
      )}

      {twoFactor && (
        <InlineAlert variant="warning" title="This account needs a second factor">
          <p>
            Your password was accepted. This account has two-factor authentication switched on,
            and the screen that asks for the code is not built in the web client yet — signing
            in from the mobile application will work in the meantime.
          </p>
        </InlineAlert>
      )}

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

      <Field label="Password" required error={fieldErrors['password']}>
        <TextInput
          type="password"
          name="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
      </Field>

      {/*
        THE SUBMIT IS HIDDEN ON EXACTLY ONE REFUSAL — a suspension, which retrying cannot
        change. Everything else keeps it, including the rate limit: a reader who waits out
        the window needs the button to still be there.
      */}
      {(failure === null || failure.retryable) && (
        <Pill type="submit" fullWidth size="lg" disabled={submitting}>
          {submitting ? 'Signing in' : 'Sign in'}
        </Pill>
      )}

      <p className="text-center text-sm text-white/64">
        No account yet?{' '}
        <Link
          href={registerHref(searchParams.get(RETURN_TO_PARAM))}
          className="rounded-sm text-white underline underline-offset-4 hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
        >
          Create one
        </Link>
      </p>

      {/*
        PASSWORD RESET IS #271 AND IS NOT LINKED, because there is no page behind it and no
        endpoint under it — the service has no `POST /v1/auth/forgot-password`. A "Forgot your
        password?" link that resolves to a 404 is worse on this screen than anywhere else on
        the platform: it is offered to somebody who is already locked out.
      */}
    </form>
  );
}

/** The register link keeps whatever return path the sign-in was given. */
function registerHref(rawReturnTo: string | null): string {
  const target = safeReturnPath(rawReturnTo);
  return target === null ? '/register' : `/register?${RETURN_TO_PARAM}=${encodeURIComponent(target)}`;
}

/** The device label, read once per submit. `undefined` on a runtime with no user agent. */
function deviceLabel(): string | undefined {
  return typeof navigator === 'undefined' ? undefined : deviceLabelOf(navigator.userAgent);
}
