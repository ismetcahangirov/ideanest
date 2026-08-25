'use client';

import { useEffect, useRef, useState } from 'react';
import { Link } from '../../i18n/navigation';
import { useSearchParams } from 'next/navigation';
import { CircleCheck } from 'lucide-react';
import { InlineAlert } from '@ideanest/ui';
import { confirmEmailChange, refusalDetailOf, refusalOf } from '../../lib/auth/credentials';
import { describeAuthFailure, type AuthFailure } from '../../lib/auth/failures';
import { AuthPageHeader } from './AuthPageHeader';

/**
 * §4.1's A-12, second half — where the address-change confirmation lands. Issue #277.
 *
 * <h2>Why this is a public route and not a settings screen</h2>
 *
 * `POST /v1/auth/confirm-email-change` is unauthenticated, and `CredentialController` says why
 * in one sentence: "the person following it is reading the new mailbox, which is the one place
 * they are least likely to be signed in". A landing page behind the session guard would send
 * that person to a sign-in form, and the address they would sign in with is the *old* one — the
 * account has not moved yet. So the page sits in `app/(auth)` beside `/verify-email`, which
 * solved the identical problem for A-02, and it takes that route group's frame, its `noindex`,
 * and its `robots.txt` disallow.
 *
 * <h2>It runs exactly once</h2>
 *
 * The token is spent by a conditional update — `EmailChangeRequestRepository.claim` — so a
 * second request for the same token is refused with "This link has already been used." React's
 * development mode double-invokes effects on purpose, and a component without the guard below
 * would show that refusal to every developer who ever opened it. The ref is set synchronously,
 * before the await, which is what makes the guard hold across the second invocation rather than
 * racing it. `VerifyEmailView` carries the same guard for the same reason.
 *
 * <h2>The two refusals are different outcomes, not two spellings of one</h2>
 *
 * **`invalid-verification-link`** is a link that cannot work — never issued, expired after six
 * hours, or already spent — and the service writes a different sentence for each, which is the
 * sentence printed here.
 *
 * **`email-already-in-use`** is a link that could have worked and was overtaken: somebody
 * registered the address between the request and the click. `AccountCredentialsService` rolls
 * the claim back in that case, deliberately, "so a change that becomes possible again can still
 * be confirmed" — the link is **not** spent. That is a materially different thing to tell
 * somebody, and it is why this screen does not fold the two into one apology.
 *
 * <h2>It does not sign anybody in, and it does not sign anybody out</h2>
 *
 * An address change revokes no sessions: nothing about the credential changed, and the sessions
 * were issued to the same person. Somebody already signed in elsewhere stays signed in and
 * their address has moved under them, which is why the old address is written to as well.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5's authentication row. The status is announced through a live region
 * instead, because a screen whose only change is a heading swapping is a screen a screen-reader
 * user is not told about.
 */

type Status = 'idle' | 'confirming' | 'confirmed' | 'refused' | 'taken';

export function EmailChangeConfirmView() {
  const searchParams = useSearchParams();
  const token = (searchParams.get('token') ?? '').trim();

  const [status, setStatus] = useState<Status>(token === '' ? 'idle' : 'confirming');
  const [failure, setFailure] = useState<AuthFailure | null>(null);
  const [detail, setDetail] = useState<string | null>(null);
  const attempted = useRef(false);

  useEffect(() => {
    if (token === '' || attempted.current) return;
    attempted.current = true;

    void (async () => {
      try {
        await confirmEmailChange(token);
        setStatus('confirmed');
      } catch (cause) {
        const refusal = refusalOf(cause);
        setDetail(refusalDetailOf(cause));

        if (refusal === 'email-already-in-use') {
          setStatus('taken');
          return;
        }
        if (refusal === 'invalid-verification-link') {
          setStatus('refused');
          return;
        }

        // A 429, an outage, or anything else that is not about this link.
        setFailure(describeAuthFailure(cause));
        setStatus('refused');
      }
    })();
  }, [token]);

  return (
    <div>
      {/*
        `polite`, because finishing is an outcome the reader asked for rather than an
        interruption, and a permanent region whose text changes rather than one that appears —
        a live region inserted with its message already in it is read as ordinary content by
        several screen readers instead of being announced.
      */}
      <p role="status" aria-live="polite" className="sr-only">
        {status === 'confirming'
          ? 'Confirming your new email address.'
          : status === 'confirmed'
            ? 'Your email address has been changed.'
            : status === 'taken'
              ? 'That address now has an account, so the change could not be completed.'
              : status === 'refused'
                ? 'This confirmation link could not be used.'
                : ''}
      </p>

      {status === 'idle' && (
        <>
          <AuthPageHeader title="Open the link we sent you">
            This page finishes moving an account to a new email address, and it needs the link
            sent to that address to do it. Opening that link brings you back here with
            everything it needs.
          </AuthPageHeader>
          <FooterLinks />
        </>
      )}

      {status === 'confirming' && (
        <AuthPageHeader title="Confirming your new address">
          One moment — we are checking the link.
        </AuthPageHeader>
      )}

      {status === 'confirmed' && (
        <>
          <div className="mb-8 flex items-start gap-3">
            {/*
              Colour plus an icon plus the words, never colour alone (§9.2). `--success` and
              not lime, which means "act now" and would say the opposite of "done" (§2.4).
            */}
            <CircleCheck aria-hidden="true" className="mt-1 size-6 shrink-0 text-[var(--success)]" />
            <AuthPageHeader title="Your email address has been changed">
              This is the address you sign in with from now on. Your password has not changed
              and any browser that was signed in still is — we have written to the previous
              address to say the account moved.
            </AuthPageHeader>
          </div>

          <Link
            href="/settings/email"
            className="inline-flex h-12 w-full items-center justify-center rounded-full bg-white px-6 text-base font-medium text-on-white transition-colors duration-150 ease-in-out hover:bg-[var(--white-muted)]"
          >
            Go to your account
          </Link>
        </>
      )}

      {status === 'taken' && (
        <>
          <AuthPageHeader title="That address was taken first" />

          <InlineAlert variant="warning" title="The change could not be completed" className="mb-6">
            <p>{detail ?? 'That address now has an account. Ask for the change again.'}</p>
          </InlineAlert>

          <div className="rounded-lg border border-white/8 bg-surface-2 p-5 text-[15px] leading-relaxed text-white/64">
            <p>
              Somebody registered that address between the moment the change was asked for and
              the moment this link was opened. Your account is unchanged and still signs in with
              its previous address.
            </p>
            <p className="mt-3">
              {/*
                THIS LINK IS NOT SPENT, which is worth saying: the service rolls the claim back
                on this refusal precisely so a change that becomes possible again can still be
                confirmed.
              */}
              This link has not been used up. If that address becomes free, opening it again
              still works — otherwise ask for the change again with a different address.
            </p>
          </div>

          <FooterLinks />
        </>
      )}

      {status === 'refused' && (
        <>
          <AuthPageHeader title="This link cannot be used" />

          <InlineAlert
            variant="danger"
            title={failure?.title ?? 'Confirmation refused'}
            className="mb-6"
          >
            {/* The service's own sentence: only it knows whether this link expired, was already
                used, or was never one. */}
            <p>{failure?.detail ?? detail ?? 'This link cannot be used.'}</p>
          </InlineAlert>

          <div className="rounded-lg border border-white/8 bg-surface-2 p-5 text-[15px] leading-relaxed text-white/64">
            <p>
              A confirmation link works for six hours and can be used once. Your account has not
              moved — it still signs in with the address it had — so ask for the change again
              from your account settings and open the newest message.
            </p>
          </div>

          <FooterLinks />
        </>
      )}
    </div>
  );
}

function FooterLinks() {
  return (
    <p className="mt-8 text-center text-sm text-white/64">
      <Link
        href="/settings/email"
        className="rounded-sm text-white underline underline-offset-4 hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
      >
        Email settings
      </Link>
      {' · '}
      <Link
        href="/sign-in"
        className="rounded-sm text-white underline underline-offset-4 hover:text-white/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
      >
        Sign in
      </Link>
    </p>
  );
}
