'use client';

import { useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { MailCheck } from 'lucide-react';
import { Field, InlineAlert, Pill, TextInput } from '@ideanest/ui';
import { register } from '../../lib/auth/api';
import { describeAuthFailure, fieldErrorsOf, type AuthFailure } from '../../lib/auth/failures';
import { RETURN_TO_PARAM, safeReturnPath } from '../../lib/auth/redirect';
import { AuthPageHeader } from './AuthPageHeader';

/**
 * §4.1's A-01 — account creation, and the "check your email" state that follows it. Issue
 * #269.
 *
 * <h2>The success state is the interesting half</h2>
 *
 * `POST /v1/auth/register` answers **202 whether or not the address was already registered**,
 * and `AuthController` gives the reason at length: an endpoint that answered differently for a
 * known address hands anybody with a breach list the subset of it that has an account here.
 *
 * That decision only holds if the client honours it, and honouring it has a consequence worth
 * stating plainly: **this form cannot tell somebody "that address is already registered", and
 * must not try.** So the screen it shows afterwards is written to be true either way — it says
 * what was sent to the address, not what was created. Somebody who already had an account gets
 * a message telling them so, at the address, which is where it belongs.
 *
 * It also means there is nothing to sign in with yet. Registration issues no session; the
 * verification link is what makes the account usable, and the screen says so rather than
 * leaving somebody waiting on a redirect that is not coming.
 *
 * <h2>The password rule is the service's</h2>
 *
 * `RegistrationRequest` deliberately does not annotate a length — the policy decides, "so that
 * one place decides what is acceptable and the message a user sees is the same wherever a
 * password is set". A minimum typed into this form would be a second opinion that goes stale
 * the day the policy changes. What is shown before submitting is the hint the service's own
 * refusal would say, and what is shown after a refusal is that refusal.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5's authentication row. The success state replaces the form outright
 * rather than transitioning into it.
 */

export function RegisterForm() {
  const searchParams = useSearchParams();

  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<AuthFailure | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({});
  const [sentTo, setSentTo] = useState<string | null>(null);

  const returnTo = searchParams.get(RETURN_TO_PARAM);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;

    setSubmitting(true);
    setFailure(null);
    setFieldErrors({});

    const address = email.trim();

    try {
      await register({ email: address, password, name: name.trim() });
      setSentTo(address);
    } catch (cause) {
      setFailure(describeAuthFailure(cause));
      setFieldErrors(fieldErrorsOf(cause));
    } finally {
      setSubmitting(false);
    }
  }

  if (sentTo !== null) {
    return (
      <div>
        <AuthPageHeader title="Check your email">
          {/*
            THE ADDRESS IS ECHOED BECAUSE A TYPO IS THE MOST COMMON REASON NOTHING ARRIVES,
            and it is the reader's own address rather than anything the service disclosed.
          */}
          We have sent a message to <span className="text-white">{sentTo}</span>. Open the link
          in it to finish setting up your account.
        </AuthPageHeader>

        <div className="flex items-start gap-3 rounded-lg border border-white/8 bg-surface-2 p-5 text-[15px] leading-relaxed text-white/64">
          <MailCheck aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-white/40" />
          <div>
            <p>
              The link is valid for 24 hours and can be used once.
            </p>
            <p className="mt-3">
              If that address already had an IdeaNest account, the message says so and offers a
              way back in instead of creating a second one.
            </p>
          </div>
        </div>

        <p className="mt-8 text-center text-sm text-white/64">
          Already verified?{' '}
          <Link
            href={signInHrefWith(returnTo)}
            className="rounded-sm text-white underline underline-offset-4 hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            Sign in
          </Link>
        </p>
      </div>
    );
  }

  return (
    <>
      {/*
        THE HEADING IS HERE RATHER THAN ON THE PAGE, because which of the two is correct
        depends on a state only this component holds — see the route's comment.
      */}
      <AuthPageHeader title="Create an account">
        Back campaigns, follow creators, and start a campaign of your own. It takes an email
        address and a password.
      </AuthPageHeader>

      <form onSubmit={submit} noValidate className="flex flex-col gap-5">
        {failure !== null && (
          <InlineAlert variant="danger" title={failure.title}>
            <p>{failure.detail}</p>
          </InlineAlert>
        )}

        <Field
          label="Name"
          required
          hint="How you appear to backers and creators. You can change it later."
          error={fieldErrors['name']}
        >
          <TextInput
            name="name"
            autoComplete="name"
            maxLength={80}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </Field>

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

        <Field
          label="Password"
          required
          hint="Long is stronger than complicated. The exact requirement comes from the service if this one is refused."
          error={fieldErrors['password']}
        >
          <TextInput
            type="password"
            name="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </Field>

        <Pill type="submit" fullWidth size="lg" disabled={submitting}>
          {submitting ? 'Creating your account' : 'Create account'}
        </Pill>

        <p className="text-center text-sm text-white/64">
          Already have an account?{' '}
          <Link
            href={signInHrefWith(returnTo)}
            className="rounded-sm text-white underline underline-offset-4 hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            Sign in
          </Link>
        </p>

        {/*
          NO "BY REGISTERING YOU AGREE TO THE TERMS" LINE. §22 owns that copy and #293 is
          `status: needs-decision` on it, so there is no document to agree to. A consent
          sentence pointing at a page that does not exist is a worse defect than its absence:
          it is a claim that somebody accepted something nobody has written.
        */}
      </form>
    </>
  );
}

/** The sign-in link keeps whatever return path this page was given. */
function signInHrefWith(rawReturnTo: string | null): string {
  const target = safeReturnPath(rawReturnTo);
  return target === null ? '/sign-in' : `/sign-in?${RETURN_TO_PARAM}=${encodeURIComponent(target)}`;
}
