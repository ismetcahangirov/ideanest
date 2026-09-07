# Epoint

What Epoint can do, taken from **Epoint's own API documentation, "Epoint.az —
Elektron ödəniş platforması, versiya 1.0.3"**, read on 7 September 2026, together
with a working production integration against the same API
(`Alievs-corp/LuxMart`, `backend/products/payment_controllers.go`) which confirms
the request and callback shapes in practice.

This file is §9.3's record and #422's deliverable. It exists so that a later
reader can tell **what was promised, by whom, and what was merely inferred**, and
so that `EpointPaymentProvider.capabilities()` is written from a source rather
than from hope.

---

## What this document is, and what it is not

It is Epoint's published integration specification. It is not a countersigned
annex, and it is not an answer to the four questions #422 asks about R-10. Six of
§9.3's fourteen rows are simply **not addressed by it**, and this file says so on
each row rather than rounding them up to a yes.

Two consequences follow, and both are enforced in code rather than described here:

- Every capability `EpointPaymentProvider` reports is a **configuration value**
  under `ideanest.payment.epoint.capabilities`, defaulted from the table below.
  A deployment that obtains a different answer in writing changes a property; it
  does not edit an adapter.
- The rows this document does not answer default to **false**, and two of them —
  R-03 and R-08 — are checked at start-up. A deployment that names Epoint as its
  primary provider without having turned them on **does not start**. That is
  §9.4's existing rule (`PaymentProviders` refuses an adapter that cannot do
  R-01, R-02 and R-03) reaching the one row Epoint's documentation is silent on.

---

## The fourteen rows

| # | Requirement | What the documentation says | Answer |
|---|---|---|---|
| R-01 | Card tokenisation (card-on-file) | `POST /api/1/card-registration` returns a `card_id`, "ödənişləri yerinə yetirmək üçün istifadə edilməsi lazım olan unikal kart identifikatoru". `POST /api/1/execute-pay` charges that `card_id`. `refund=0` registers a card for collection; `refund=1` registers one for transfers out | **Yes.** But the document nowhere states **how long a `card_id` stays chargeable**, and §9.1's whole design is a charge sixty days after the pledge. Still to be confirmed |
| R-02 | Merchant-initiated transactions | `/api/1/execute-pay` takes `public_key`, `card_id`, `order_id`, `amount`, `currency`. There is no redirect in the response and no cardholder step: it is a server-to-server charge of a stored card | **Yes, by construction.** The document does not use the scheme's own words for it, so a deployment that wants the phrase in writing should ask |
| R-03 | Scheme transaction chaining | **Not addressed.** No request field carries a scheme transaction identifier, and none is returned. `rrn` — "Retrieval Reference Number, unikal əməliyyat identifikatoru, yalnız uğurlu bir əməliyyat üçün mövcuddur" — is the closest thing the API exposes | **Unanswered.** Epoint is the merchant of record and holds the card, so chaining is Epoint's to perform and not the platform's; but that is an inference. The adapter carries the registration's `rrn` as `schemeTransactionId`, and `capabilities.scheme-chaining` defaults to **false**, which stops the service |
| R-04 | 3-D Secure | Not named. §"Epoint ödəniş sisteminin işləmə prinsipi" step 2 sends the customer to "bankın ödəniş səhifəsi" — the issuer's page, which is where a 3-D Secure challenge is presented | **Inferred, not confirmed.** The liability shift is not stated anywhere, and it is the reason R-04 is on the list |
| R-05 | Zero or minimal-value verification | `/api/1/card-registration` registers a card **without charging anything**; the charging variant is a separate endpoint, `/api/1/card-registration-with-pay` | **Yes, and the zero-amount form is the one to use** — see below |
| R-06 | Full and partial refunds | `POST /api/1/reverse` takes `transaction` and an **optional** `amount`: "Məbləğin qismən qaytarılması göstərilə bilər" | **Yes, both.** Note the naming trap below |
| R-07 | Signed webhooks | The result POST to `result_url` carries `data` and `signature`. `signature = base64_encode(sha1(private_key + data + private_key, 1))`; `data = base64_encode(json_string)`. Verification is: rebuild the signature from the received `data` and your `private_key`, compare | **Yes, with two gaps.** The signature is in the **body**, not a header, and **nothing is signed with a timestamp** — see "What R-07 does not give us" below |
| R-08 | Idempotency | **Not addressed.** There is no idempotency header, no replay semantics, and no statement about what a repeated `order_id` does. `order_id` is only described as "Tətbiqinizdə unikal əməliyyat ID" | **Unanswered.** The adapter sends its idempotency key **as** `order_id`, which is at-most-once only if Epoint refuses a duplicate. `capabilities.idempotent-order-id` defaults to **false** and the adapter refuses to charge without it |
| R-09 | Batch throughput and rate limits | **Not addressed.** No sustained rate, no burst ceiling, no documented behaviour at the ceiling | **Unanswered.** `PaymentProperties.Collection.chargesPerPass` stays at 100 a minute (≈1.7 req/s), which is the figure to put in front of Epoint rather than discover |
| R-10 | Split payment / sub-merchant | `POST /api/1/split-request`, `/api/1/split-execute-pay`, `/api/1/split-card-registration-with-pay`. `split_user` is "Epoint sistemindəki ikinci istifadəçi identifikatoru" and `split_amount` is that user's share. "İkinci istifadəçi üçün ödənilən məbləğ yalnız onun ödəniş siyahısında görünəcək" | **The mechanism exists. The four questions that matter do not have answers** — see below. This is the row #71 and #423 are waiting on, and reading "split-request exists" as "the platform never holds third-party funds" would be exactly the mistake #422 was written to prevent |
| R-11 | Multi-currency | Every endpoint documents `currency` as "Mümkün dəyərlər: **AZN**". One value, on every call | **Answered, and the answer is no.** AZN only. §21.2's display rate becomes the entire currency story, and an international backer pays in AZN |
| R-12 | Wallet payments | Apple Pay for web **and** mobile applications; Google Pay for web, and for mobile only through a native integration that requires a Merchant ID Epoint issues after reviewing screenshots. Widget URL from `POST /api/1/token/widget`; the result arrives as a `postMessage` from the iframe | **Yes**, with the mobile Google Pay path gated on Epoint's review |
| R-13 | Chargeback notifications | **Not addressed.** The payment statuses are `new`, `success`, `returned`, `error`, `server_error`. There is no dispute event, no chargeback status, and nothing that corresponds to §9.8's step 1 | **Unanswered, and this one is a hole rather than a detail.** #68 built `Dispute`, `DisputeState`, `DisputeEvidence` and `DisputeService`, and on this documentation **nothing can ever open one**. See #434 |
| R-14 | Sandbox | **Not addressed.** No sandbox host, no test credentials, no statement about simulating declines. One sample response leaks an internal host (`https://epointv1.test`), which is not an offer of one | **Unanswered.** Everything below is therefore tested against a recorded contract rather than against Epoint |

---

## R-05: use the zero-amount registration, and here is why

§9.3 asks for "zero **or** minimal-value verification" and Epoint offers both, on two
endpoints. The adapter chooses by `TokenizationRequest.verificationAmount`, and the
right configuration is zero.

**There is no void.** §9.2's phase one is four steps and the fourth is "the
authorisation is voided immediately". Epoint's API has no void operation: the nearest
thing is `/api/1/reverse`, which is a reversal of a settled charge and appears on the
backer's statement as a payment and a refund rather than as nothing at all. So a
minimal-value verification through `/api/1/card-registration-with-pay` **takes money
from a backer at pledge time and does not give it back on its own**.

That is a product decision, not an implementation detail. The zero-amount path —
`/api/1/card-registration` — registers the card, returns a `card_id`, and charges
nothing, which is what §9.2 wanted and could not have from an authorisation hold.

**What the zero-amount path costs.** Epoint's plain registration takes no `order_id`,
so there is no merchant-side reference to ask `get-status` about later. The adapter
mints the session identifier as `orderId|cardId` and carries both itself. It works,
and it is worth asking Epoint whether `card-registration` will accept an `order_id`.

---

## R-10, and the four questions that are still open

§9.3 marks R-10 optional. For this platform it is not, because it may decide
whether the platform holds third-party funds at all — which is #71's question,
which decides whether an authorisation is required.

The documentation establishes only that a split **mechanism** exists. It does not
answer:

1. **Can a creator be onboarded as an Epoint user, and what does Epoint require of
   them** — registration documents, VÖEN, a bank account? `split_user` is an
   identifier in Epoint's system; how one is obtained is not described.
2. **Is the split executed at authorisation, at capture, or at settlement?** The
   document says only that the second user's share "yalnız onun ödəniş siyahısında
   görünəcək" — appears in their payment list.
3. **Who is the merchant of record for each leg?**
4. **Who does a chargeback debit — the platform, or the sub-merchant?**

Until those four have answers, **§9.5 stands as drawn**: collect to the platform,
hold for fourteen days, pay out. #435 implements that shape and says so.

---

## What R-07 does not give us

Two things, and both change how the webhook is handled.

**The signature is in the body.** Epoint POSTs `data` and `signature` as form
fields — `application/x-www-form-urlencoded` per the specification, and
`multipart/form-data` in the LuxMart integration. `PaymentProvider.parseWebhook`
takes the raw bytes, which is exactly right: the adapter extracts the `data`
field's value from those bytes and verifies the signature over **that string**,
before anything is base64-decoded or parsed as JSON.

**Nothing carries a signed timestamp.** §17.2's replay window
(`ideanest.payment.webhooks.tolerance`, five minutes) is driven from
`PaymentEvent.signedAt`, and `ProviderWebhooks.refuseReplay` already treats a null
`signedAt` as "no window to check". So for Epoint the **only** replay control is
`provider_webhook_events`' unique index over `(provider, provider_event_id)`,
which is durable and survives a restart — but it means a captured delivery stays
replayable for ever if it names an event that was never processed. That is a
finding for Epoint, not a field to invent.

**And there is no event id.** Epoint's callback carries `order_id` and
`transaction`, neither of which is an identifier for the *delivery*. The adapter's
deduplication identity is therefore `transaction:status`, and both halves are
load-bearing:

- `order_id` alone would be wrong, because §9.6 makes up to four attempts against
  one pledge and each is a different order — the identity would never collide with
  anything and would deduplicate nothing.
- `transaction` alone would be worse than wrong. A charge that settles and is later
  returned produces two deliveries about **one** Epoint transaction, and an identity
  naming only the transaction would make the second look like a redelivery of the
  first. `provider_webhook_events`' unique index would swallow the refund silently:
  the platform would have been told the money went back and would have recorded
  nothing.

---

## Naming trap: `refund-request` is the payout, `reverse` is the refund

The two are easy to swap and swapping them sends money to the wrong place.

| Endpoint | Azerbaijani heading | What it is |
|---|---|---|
| `POST /api/1/reverse` | "Əməliyyatın ləğvi sorğusu" | **The refund.** Takes `transaction` and an optional `amount`. This is `PaymentProvider.refund` |
| `POST /api/1/refund-request` | "Vəsaitlərin köçürülməsi sorğusu" | **The payout.** Takes a `card_id` and sends money *to* that card. This is `PaymentProvider.payout` |

A `card_id` used for a payout is one registered with `refund=1` — "Kart növü: 0 —
ödəniş üçün kart; 1 — vəsaitlərin köçürülməsi üçün kart". That is what #432's
creator-supplied, verified destination becomes.

---

## The endpoints this platform uses

All under `https://epoint.az`, all `POST` with two form fields, `data` and
`signature`, unless stated.

| Call | Endpoint | Notes |
|---|---|---|
| `beginTokenization` | `/api/1/card-registration` | `refund=0`. Returns `status`, `redirect_url`, `card_id` |
| `resolveTokenization` | `/api/1/get-status` | By `order_id` |
| `chargeStoredCard` | `/api/1/execute-pay` | `card_id` + `order_id` + `amount` + `currency` |
| `refund` | `/api/1/reverse` | `transaction` + optional `amount` for a partial |
| `payout` | `/api/1/refund-request` | `card_id` of a `refund=1` registration |
| `parseWebhook` | — | Epoint POSTs to the configured `result_url` |
| liveness | `GET /api/heartbeat` | Answers `{"status": "ok"}`. Not used by the adapter; useful in a runbook |

Not used, and deliberately: `/api/1/request` (hosted checkout for a card the
platform has not stored), `/api/1/pre-auth-request` and `/api/1/pre-auth-complete`
(§9.1 rejected authorisation holds), `/api/1/split-*` (R-10 is unanswered),
`/api/1/wallet/*`, `/api/1/invoices/*`, `/api/1/token/widget`.

### Signing, exactly

```
data      = base64(utf8(json_string))
signature = base64(sha1(private_key + data + private_key))
```

`sha1` over the raw bytes of the concatenation; `base64` of the twenty-byte
digest, not of its hex rendering. The same construction verifies an incoming
callback, which means **the signature is symmetric**: anybody holding the private
key can mint a delivery, and the private key is therefore the whole of the
platform's authentication for that endpoint. It is held as a secret and is on
`Redaction`'s list.

### Statuses

| Value | Where | Meaning |
|---|---|---|
| `success` | request, callback, get-status | Approved |
| `failed` | callback (observed in practice) | Refused |
| `error` | request, get-status | Refused |
| `new` | get-status | Registered by Epoint, not yet decided |
| `returned` | get-status | The payment was returned |
| `server_error` | get-status | Epoint could not answer — **not a decline** |

`code` carries the bank's response code: `000` approved, `1xx` decline, `2xx`
pick-up, `9xx` a system or format refusal. `operation_code` distinguishes `001`
card registration, `100` a customer payment, `200` a registration that charged.

The full bank response code table is in the source document, §"Bank cavab
kodları", and is not reproduced here — the adapter treats anything that is not
`000` on a non-approved status as a decline code and passes it through to
`transactions.failure_code` unchanged.

---

## What `capabilities()` reports, and where it comes from

Defaults, from the table above. Every one is a property under
`ideanest.payment.epoint.capabilities`.

| Field | Default | Source |
|---|---|---|
| `cardOnFile` | `true` | R-01, documented |
| `merchantInitiated` | `true` | R-02, documented by construction |
| `schemeChaining` | **`false`** | R-03, **not addressed** — start-up refuses until confirmed |
| `preAuthHoldDays` | `null` | Epoint has pre-auth, §9.1 does not use it, and no hold length is documented |
| `splitPayment` | **`false`** | R-10's mechanism exists; its four questions do not have answers |
| `partialRefund` | `true` | R-06, documented |
| `wallets` | `APPLE_PAY`, `GOOGLE_PAY` | R-12, documented |
| `currencies` | `AZN` | R-11, documented, and it is the only value |

`idempotentOrderId` is not on `ProviderCapabilities` — R-08 has no field there —
so it is a property the adapter reads directly and refuses to charge without.

---

## What is still owed, and by whom

Six rows, and the first three block the platform rather than degrade it.

| Row | Who to ask | What blocks on it |
|---|---|---|
| R-03 scheme chaining | Epoint | **Start-up.** The adapter registers and the service refuses to boot until this is confirmed and configured |
| R-08 idempotency | Epoint | **Every charge.** The adapter refuses to charge until this is confirmed |
| R-14 sandbox | Epoint | Nothing can be tested against Epoint. #433's suites run against a recorded contract |
| R-13 chargebacks | Epoint | §9.8 cannot start. #68's machinery stays unreachable — see #434 |
| R-09 rate limits | Epoint | A campaign with four thousand backers closing at midnight is the load |
| R-10's four questions | Epoint, then a lawyer | #71, #423, and whether §9.5 is the right diagram at all |

**The questions themselves are written out in `epoint-open-questions.md`**, in the
form to put to Epoint, with what each answer changes here. Ask them together rather
than one at a time: R-10's answer changes which of two platforms this is, and the
others are cheap to add to the same message.

**#60 does not close on this file.** #60 asks for the fourteen capabilities
confirmed in writing, and six of them are not. What this file does is reduce #60
from "no provider has been evaluated" to "one provider has been evaluated against
its own specification, and these six rows are outstanding" — and it makes the
outstanding ones refuse rather than assume.

§9.3 also asks for a second provider, and this is not that. Payriff and Azericard
stay in `ProviderName` as candidates and the second adapter is follow-up work
rather than a silent omission.
