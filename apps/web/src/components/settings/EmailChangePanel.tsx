'use client';

import { useState, type FormEvent } from 'react';
import { MailCheck } from 'lucide-react';
import { Field, InlineAlert, Pill, TextInput } from '@ideanest/ui';
import {
  refusalDetailOf,
  refusalOf,
  requestEmailChange,
} from '../../lib/auth/credentials';
import { describeAuthFailure, fieldErrorsOf, type AuthFailure } from '../../lib/auth/failures';
import { FormErrorSummary } from '../auth/FormErrorSummary';
import { useSession } from '../session/SessionProvider';

/**
 * §4.1's A-12 — moving the account to another email address. Issue #277.
 *
 * <h2>Nothing changes when this succeeds, and the screen has to be built around that</h2>
 *
 * `POST /v1/auth/change-email` answers **202, not 204**, and `CredentialController` says the
 * difference is the point: `users.email` moves when the new address follows its link, and until
 * then the old address still signs in, still receives, and still resets. V44 carries the
 * reasoning — writing the address immediately means one typo puts the account behind a mailbox
 * nobody can read, and both sign-in and the reset that would fix it go to the address on the
 * account.
 *
 * **So the confirmation below says what was sent, never what was changed**, and it keeps saying
 * which address is still the account's. The tempting version — "Your email address is now
 * new@example.com" — is false for as long as the link is unopened, which may be for ever, and
 * the person it is false to is the one who can no longer find their account.
 *
 * There is nothing to re-read afterwards either. `GET /v1/me` still answers the old address,
 * because the old address is still the answer, so this panel does not call `refresh()` to
 * discover a fact it already knows.
 *
 * <h2>Both addresses are written to, and only one of them can act</h2>
 *
 * The new address gets the link. The old one gets a notice with **no link at all**: it cannot
 * approve the change and does not need to. What it is for is that somebody losing their account
 * finds out at the address they still hold, while they still hold it. A-12's own wording is
 * "confirmation to both addresses", and the screen says which message goes where rather than
 * leaving somebody to wonder why two arrived.
 *
 * <h2>An address change revokes nothing</h2>
 *
 * Unlike A-13. Nothing about the credential changed and the sessions were issued to the same
 * person, so this form does not warn about a sign-out and must not: a warning about a
 * consequence that does not happen is the same defect as silence about one that does.
 *
 * <h2>The two refusals go under the field each is about</h2>
 *
 * `incorrect-password` is a **403** — the token was fine and the second check failed — so it
 * sits under **Current password** and the session is untouched. `email-already-in-use` is a
 * **409**, which `EmailAlreadyInUseException` argues at length is not registration's
 * enumeration oracle: the caller is signed in, the endpoint is rate limited per account, and a
 * change that silently did nothing "would leave somebody waiting for a confirmation that is
 * never coming". So the sentence is shown, under the address field.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 — "authentication, account settings: none".
 */
export function EmailChangePanel() {
  const { session } = useSession();

  const [currentPassword, setCurrentPassword] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<AuthFailure | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({});
  const [requestedFor, setRequestedFor] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (busy) return;

    setBusy(true);
    setFailure(null);
    setFieldErrors({});

    const address = newEmail.trim();

    try {
      await requestEmailChange({ currentPassword, newEmail: address });
      setCurrentPassword('');
      setNewEmail('');
      setRequestedFor(address);
    } catch (cause) {
      const refusal = refusalOf(cause);
      const detail = refusalDetailOf(cause);

      setFailure(describeAuthFailure(cause));
      setFieldErrors({
        ...(refusal === 'incorrect-password' && detail !== null
          ? { currentPassword: detail }
          : {}),
        ...(refusal === 'email-already-in-use' && detail !== null ? { newEmail: detail } : {}),
        ...fieldErrorsOf(cause),
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="rounded-2xl border border-white/8 bg-surface-2 p-6 sm:p-8">
      <h2 className="text-lg font-medium tracking-[-0.02em] text-white">
        Change your email address
      </h2>

      {/*
        THE ADDRESS ON THE ACCOUNT IS SHOWN THROUGHOUT, including on the confirmation below,
        because until a link is opened it is still the answer. `GET /v1/me` returns it in full —
        the person reading it is the person it belongs to.
      */}
      {session !== null && (
        <p className="mt-2 text-sm text-white/64">
          You sign in with{' '}
          <span className="font-medium text-white">{session.email}</span>
          {session.emailVerified ? '.' : ', which is not verified yet.'}
        </p>
      )}

      {requestedFor !== null ? (
        <div className="mt-6 flex flex-col gap-6">
          <div className="flex items-start gap-3 rounded-xl border border-white/8 bg-surface-1 p-5 text-[15px] leading-relaxed text-white/64">
            <MailCheck aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-white/40" />
            <div>
              <p>
                A confirmation link is on its way to{' '}
                <span className="font-medium text-white">{requestedFor}</span>. Opening it is
                what moves the account.
              </p>
              <p className="mt-3">
                {/*
                  Said plainly, because it is the one thing somebody can get wrong here: they
                  see a success message, assume the account has moved, and stop being able to
                  find it when the link goes unopened.
                */}
                <strong className="font-medium text-white">
                  Nothing has changed yet.
                </strong>{' '}
                {session === null
                  ? 'Your current address still signs in'
                  : `You still sign in with ${session.email}`}{' '}
                until that link is opened, and it works for six hours.
              </p>
              <p className="mt-3">
                We have also written to your current address to say the change was asked for.
                That message carries no link — it cannot approve anything, and it is there so a
                change you did not make reaches you while the account is still yours.
              </p>
            </div>
          </div>

          <div>
            <Pill type="button" variant="ghost" onClick={() => setRequestedFor(null)}>
              Ask for a different address
            </Pill>
          </div>
        </div>
      ) : (
        <form onSubmit={submit} noValidate className="mt-6 flex max-w-[30rem] flex-col gap-5">
          <FormErrorSummary failure={failure} />

          <InlineAlert variant="info" title="The account moves when the new address answers">
            <p>
              We send a link to the address you give us and a notice to the one you have now.
              Your account keeps its current address until that link is opened, so a typo costs
              you nothing but the message.
            </p>
          </InlineAlert>

          <Field
            label="Current password"
            required
            hint="Asked for so that a stolen sign-in cannot move the address that resets your password."
            error={fieldErrors['currentPassword']}
          >
            <TextInput
              type="password"
              name="currentPassword"
              autoComplete="current-password"
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
            />
          </Field>

          <Field label="New email address" required error={fieldErrors['newEmail']}>
            <TextInput
              type="email"
              name="newEmail"
              autoComplete="email"
              inputMode="email"
              value={newEmail}
              onChange={(event) => setNewEmail(event.target.value)}
              placeholder="you@example.com"
            />
          </Field>

          <div>
            <Pill type="submit" disabled={busy}>
              {busy ? 'Sending the confirmation' : 'Send the confirmation'}
            </Pill>
          </div>
        </form>
      )}
    </section>
  );
}
