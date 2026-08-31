# Auth transactional email — #86's second half

**Date:** 2026-08-31
**Status:** implemented on `feat/auth-transactional-email`

## The problem

`#86` built a mail transport, and only the notification module got one.
`EmailChannelSender` sends every `NotificationType` through Spring Mail, with
typed templates, a shared envelope and an `email_deliveries` ledger. The auth
module's six messages went nowhere: the only implementation of
`VerificationNotifier` was `LoggingVerificationNotifier`, which wrote a line
saying a message would have been sent and — under the `local` profile only — the
token itself.

The visible consequence was that registration could not be completed in any
deployed environment. Registration accepted the request, created the account,
issued a token and returned 202; the web client showed its "check your email"
state; nothing arrived. The same held for password reset and for both halves of
an address change.

## The constraint that shaped the design

`ModuleBoundaryTests` fails the build when one module reaches into another's
`.domain` or `.infrastructure` package. `MimeEmails`, `EmailRenderer`,
`EmailContent` and `RenderedEmail` are all `notification.infrastructure`, so the
auth module cannot name any of them. Before this change no module depended on
`notification` at all.

Three ways out were considered.

1. **Move the email machinery to `notification.application`.** Correct-looking
   and the largest diff: `EmailComposer`, `EmailTemplates` and three test suites
   all name `EmailContent`, and none of that churn serves the goal.
2. **Compose the auth copy inside the notification module.** Legal, and it puts
   the wording of a password-reset email in the module that owns campaign
   notifications. Ownership of the copy would then be in the wrong place
   permanently.
3. **Publish a port in `notification.application`.** Chosen. It mirrors
   `ChannelSender`, the pattern the module already uses, and it is the smallest
   thing that is also the right shape for the collaborator-invitation and
   launch-reminder notifiers when their copy is written.

## What was built

**`notification.application`** — the published contract:

* `TransactionalMail` — subject, headline, paragraphs, action label, action
  **path**. The same five fields as `EmailContent`, in a package callers may
  name.
* `TransactionalMailer` — `send(toAddress, toName, mail, locale)`.
* `TransactionalMailFailedException` — so a caller can catch a refusal without
  naming a JavaMail type.

The record carries a path rather than a URL. Resolving it against
`ideanest.notification.email.base-url` is the adapter's job, which keeps one
place deciding the origin — a preview environment must not send its readers to
production.

**`notification.infrastructure.MimeTransactionalMailer`** — maps
`TransactionalMail` to `EmailContent` and reuses `EmailRenderer` and
`MimeEmails` unchanged. No second envelope, no second layout.

**`auth.infrastructure`**:

* `AuthEmailComposer` — the six messages, from `email.auth.*` keys in the four
  catalogues.
* `SmtpVerificationNotifier` — implements `VerificationNotifier`, replacing
  `LoggingVerificationNotifier`, which is deleted (two `@Component`s
  implementing one port is a start-up failure).

## Decisions worth recording

**The footer loses a line.** `EmailRenderer.render` gained a
`preferencesApply` flag and both layouts guard on it. The notification footer
offers to change which emails you get; on a password reset that is false, and
false in a direction that invites somebody to look for a switch that must never
exist. An account whose owner had turned off "your password was changed" is a
takeover nobody is told about.

**No name in the greeting.** `EmailComposer` opens with the recipient's name.
Auth holds an `EmailAddress` and no profile, and half of these messages go to
addresses with no confirmed account behind them — including one that must never
reveal whether an account exists. Looking a name up would be a database round
trip, a module dependency and an enumeration risk, so the copy addresses nobody
by name.

**No expiry in the copy.** Every link has a TTL in `AuthProperties`, and the
reset link's is one hour against the verification link's twenty-four. One
sentence that reads correctly at both needs three plural forms in Russian. The
copy says to ask for another link if this one has expired, which is true at every
duration and is also the only sentence that says what to do.

**A refusal is logged, not propagated.** These are sent from an `AFTER_COMMIT`
listener. By then the account exists, so throwing cannot undo anything — it can
only turn a successful registration into a 500, after which the person retries
and is told the address is taken. The cost is stated rather than hidden: during a
mail outage people register and no link arrives. `#135`'s outbox is what closes
that window, and this path is the shape it will drain.

**No `email_deliveries` row.** That table's rows point at a notification and
these messages are not one. What the transport did is logged, which is what
`EmailTemplates.testSend` settled for in the same position.

**The `Message-ID` is generated, not derived.** `EmailChannelSender` derives its
identifier from the notification so that an at-least-once queue's duplicate
collapses in the client. There is no queue here and no row to derive from, so the
identifier is random — an honest statement that this path has no deduplication
rather than a weaker version of one.

## Gap, named

**An expired verification link has no resend.** Registering the same address
again sends the already-registered notice, not a fresh link, because
`POST /v1/auth/register` may not answer whether an address exists. Reset and
address change both have a form behind them that issues another link;
verification does not. Out of scope here — it is an endpoint and a rate limit,
not a template.

## Verification

`AuthEmailTests` (10) drives every message against GreenMail and asserts on the
bytes: recipient, both `multipart/alternative` parts, the link built against the
configured origin, a token URL-encoded into the query string, the footer's
missing line, and that a refusing mailer throws nothing back at the caller. It
also asserts that production ships exactly one `VerificationNotifier`.

`AuthEmailCopyTests` (4) renders all six messages in all four languages and fails
on a placeholder, a `null`, a blank part, a missing footer, or a subject that fell
back to English.

`ModuleBoundaryTests` passes, which is the check the whole design is arranged
around.
