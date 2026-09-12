# Epoint — the outstanding questions

The six §9.3 rows that Epoint's specification version 1.0.3 does not answer, as
questions to put to Epoint. `docs/providers/epoint.md` is the record of what *is*
answered; this file is what is still owed and who is blocked by each row.

**Ask for the answers in writing.** §9.3's instruction is "confirm each of these in
writing before signing", and the reason is not procedural: `capabilities()` is read
at start-up and the platform trusts it absolutely. An email is enough; a signed annex
is better. When an answer arrives, record it in `epoint.md` with the date and the
person who gave it, and flip the matching property under
`ideanest.payment.epoint.capabilities`.

**Two of these stop the service.** R-03 and R-08 default to `false`, so a deployment
that names Epoint without them does not start. That is deliberate.

---

## R-03 — Scheme transaction chaining · **blocks start-up**

Scheme rules require a merchant-initiated transaction to reference the original
customer-initiated authorisation. Epoint's API carries no such identifier in either
direction.

1. When `/api/1/execute-pay` charges a stored `card_id`, is the transaction submitted
   to the scheme as a stored-credential transaction chained to the original
   registration?
2. If so, does Epoint hold and submit that chaining identifier itself, as merchant of
   record — or is the merchant expected to supply it?
3. Is the `rrn` returned by `/api/1/card-registration` the identifier of that original
   authorisation, or an Epoint-internal reference?

**Why it matters.** Without chaining, the later charge is submitted as though the
backer were present. The issuer is entitled to decline it, and to treat a pattern of
them as fraud — at a campaign's close, in front of every backer who has just been
told it succeeded.

## R-08 — Idempotency · **blocks every charge**

The specification describes `order_id` only as "Tətbiqinizdə unikal əməliyyat ID".

1. If two requests arrive with the same `order_id`, does Epoint execute the second
   or refuse it?
2. If it refuses, what does it answer — and can that answer be told apart from a
   decline?
3. Is there a retention window on `order_id` after which it may be reused?
4. Is there an idempotency header, or is `order_id` the only mechanism?

**Why it matters.** When a request times out, the platform does not know whether the
charge happened. It must be able to repeat the request safely. This is the difference
between charging a backer once and twice.

## R-14 — Sandbox · **blocks testing against Epoint**

1. Is there a test environment, and at what host?
2. Test credentials — a public key and a private key.
3. Can it simulate a decline, a timeout, and a returned payment?
4. Are there test card numbers, and do any of them exercise 3-D Secure?

## R-13 — Chargeback notifications · **blocks §9.8 entirely**

The five documented statuses — `new`, `success`, `returned`, `error`, `server_error`
— contain no dispute.

1. How is the merchant notified of a chargeback? Callback, console, or email?
2. If by callback, what does it carry, and how does it relate to the original
   `transaction`?
3. How long after the acquirer receives it?
4. How is evidence submitted, and is there an API for it?
5. How is the outcome — won or lost — communicated?

**Why it matters.** The platform has a full dispute mechanism built and nothing can
open one. Today a chargeback would be discovered in a bank statement.

## R-09 — Rate limits

1. The sustained request rate per merchant, and the burst ceiling.
2. What happens at the ceiling — a queue, a 429, or a dropped request?
3. Is there a per-endpoint limit, or one shared limit?

**The load is not hypothetical.** A campaign with four thousand backers closing at
midnight is four thousand charges. The platform is configured for a hundred a minute
— roughly 1.7 requests a second — and would rather be told that is acceptable than
discover it is not.

## R-10 — Split payments and sub-merchants · **decides the legal position**

This is the row that matters most, and it is not an optimisation. If a creator can be
an Epoint sub-merchant and the money divides at collection, the platform never holds
third-party funds — and the question of whether it needs a payment-services
authorisation may not arise at all.

1. Can a creator be onboarded as an Epoint user so as to be a `split_user`? What does
   Epoint require of them — registration documents, VÖEN, a bank account?
2. Is the split executed at authorisation, at capture, or at settlement?
3. Who is the merchant of record for each leg?
4. **Who does a chargeback debit — the platform, or the sub-merchant?**
5. Does sub-merchant onboarding validate the bank account, and is the verified
   account holder's name returned to the platform?

Question 5 is separate from the others: it is the payout-destination verification
mechanism, and it decides whether creators can be verified automatically or must be
checked by hand.

---

## Two smaller things worth asking at the same time

**Token lifetime (R-01).** How long does a `card_id` stay chargeable, and does a card
saved today remain chargeable sixty days later? The whole design is a card saved at
pledge time and charged at the campaign's close.

**3-D Secure and liability (R-04).** Is the cardholder authenticated with 3-D Secure
during `/api/1/card-registration`, and does the liability shift apply to the later
`/api/1/execute-pay` charge?

**An `order_id` on plain registration.** `/api/1/card-registration` accepts no
`order_id`, so there is no merchant-side reference to query `/api/1/get-status` with
afterwards. Would Epoint accept one?

---

## What each answer changes here

| Answer | What changes |
|---|---|
| R-03 confirmed | `capabilities.scheme-chaining=true`. The service can start |
| R-08 confirmed | `capabilities.idempotent-order-id=true`. Collection can run |
| R-14 supplied | The adapter's suites gain a live contract test |
| R-13 supplied | An Epoint event is mapped to `CHARGEBACK_OPENED` and §9.8's first arrow exists |
| R-09 supplied | `ideanest.payment.collection.charges-per-pass` is set from a number rather than a guess |
| R-10 question 4 | #71 and #423 — whether §9.5 is escrow or a split, and whether an authorisation is required |
| R-10 question 5 | #432's mechanism A, and whether destination verification is automatic |
