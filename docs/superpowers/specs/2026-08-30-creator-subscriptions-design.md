# Creator subscriptions

**Date:** 2026-08-30
**Status:** approved for implementation

Publishing a campaign becomes something a creator subscribes to. A plan sets what
the platform charges for that and what it allows; the console owns the catalogue;
a creator with no subscription is sent to the pricing page rather than refused in
place.

---

## 1. What this is for

Today anybody with an account may submit a campaign for review, and the only
limit on how many is moderation's patience. Three things follow from that, and
each of them is a reason for this work:

- **The platform has no revenue before a campaign succeeds.** §9's fee is taken
  from collected pledges, so a campaign that raises nothing costs the platform a
  moderator's afternoon and returns nothing.
- **Nothing bounds a single account.** One creator may hold twenty campaigns in
  review, and the queue is worked first-come.
- **There is no lever between "allowed" and "banned".** A creator who is a poor
  fit for the platform is either moderated one campaign at a time or suspended.

A subscription answers all three with one mechanism: publishing is a thing an
account holds an entitlement to, the entitlement carries limits, and the limits
are per plan.

## 2. What is in scope

| | |
|---|---|
| A plan catalogue | Priced, with limits, administered from the console |
| A subscription per account | Bought from a public pricing page |
| A gate on submission | `DRAFT → SUBMITTED` requires an entitlement |
| Limits enforced at that gate | How many campaigns at once, how large a goal |
| A redirect | A refused submission takes the creator to `/pricing` |

## 3. What is deliberately not in scope, and why

**Taking card payment is not built, and this specification does not pretend
otherwise.** §9.2 and `PaymentProvider`'s own header say it plainly: no provider
adapter ships, because #60 — confirm §9.3's fourteen capabilities in writing — is
unanswered. Nothing on this platform can charge a card today, pledges included.

So a paid subscription is bought in two steps rather than one:

1. The creator chooses a plan. A subscription is written in
   `PENDING_PAYMENT`, and the pricing page tells them what happens next.
2. A member of staff holding `CONFIGURE_PLATFORM` records that payment arrived,
   from the console. The subscription becomes `ACTIVE`.

That is not a stub standing in for a provider. It is how a platform with no
processor actually sells — an invoice and a bank transfer — and it is audited
under the name of whoever confirmed it. When #60 lands, the card flow replaces
step 2 and nothing above it changes: the states, the gate, the limits and the
pricing page are already the shape a provider needs.

A plan priced at zero has no step 2. It activates on the spot, because there is
no payment to wait for.

**Also out of scope:** proration, plan upgrades mid-period, invoices as
documents, dunning, and per-plan fee rates. Each is a real feature; none is
needed to make publishing a thing an account subscribes to, and §9's fee schedule
already has a home (AD-11) that a per-plan rate would compete with.

## 4. Where the gate goes

`ProjectTransitionService.submit` — `DRAFT`/`PRELAUNCH`/`CHANGES_REQUESTED` →
`SUBMITTED`.

Not `create`, and not `launch`.

- **Not `create`.** A creator must be able to build the thing they are deciding
  whether to pay for. A draft is private, costs the platform nothing, and a
  paywall in front of an empty form is a paywall in front of nothing.
- **Not `launch`.** By then the campaign has been through moderation. Refusing at
  that point spends a moderator's time and then takes it back, and a creator
  whose subscription lapsed between approval and launch would be stopped at the
  worst possible moment.

Submission is the first moment the campaign costs the platform something and the
first moment it stops being private. That is the line.

The check runs **after** the state edge and **before** the checklist:

```
requireEdge(project.getState(), ProjectState.SUBMITTED);   // are you allowed to move
publishing.requireEntitled(project);                       // may you publish at all
checklist.requireSubmittable(project);                     // is this campaign finished
```

The order is the one `submit` and `launch` already use, extended by one step, and
the reasoning is the same: report the thing the creator has to fix first. A
campaign that is in the wrong state is refused for that; an account with no
subscription is refused for that, rather than being sent to write a longer risks
section it will still not be allowed to submit.

## 5. Data

Two tables, in one migration — `V62__create_subscriptions.sql`.

### 5.1 `subscription_plans`

The catalogue. Rows, not configuration, for AD-11's reason: a price is something
an operator changes without a deployment, and the console is where they change
it.

| Column | Notes |
|---|---|
| `id` | uuid |
| `code` | Stable, unique, upper-case. What an operator and a log line agree on |
| `name`, `description` | What the pricing page shows |
| `price`, `currency` | `numeric(19,4)`. Zero is allowed and means a free tier |
| `billing_period` | `MONTHLY` or `YEARLY` |
| `max_active_campaigns` | Null means no limit |
| `goal_ceiling` | `numeric(19,4)`. Null means no ceiling |
| `listed` | Whether the pricing page offers it. A retired plan stops being sold without its subscribers losing it |
| `sort_order` | The order the pricing page draws them in |
| `created_at`, `updated_at`, `created_by` | |

**A plan is edited in place, unlike a fee schedule.** That is a deliberate
departure from V49 and it needs stating. A fee schedule may not be edited because
a past payout was computed against it and §22.1 asks what the rate was in March.
A plan carries no such history: what a subscriber was charged is written on their
subscription row (§5.2), so editing the plan cannot rewrite it. What editing a
plan *does* change is the limits of everybody currently on it — see §5.3.

**A plan is never deleted.** `listed` is how a plan leaves the catalogue. Deleting
one would either orphan its subscribers or cascade them away, and the second is
worse than the first.

### 5.2 `subscriptions`

| Column | Notes |
|---|---|
| `id` | uuid |
| `account_id` | `REFERENCES users (id) ON DELETE CASCADE` |
| `plan_id` | `REFERENCES subscription_plans (id) ON DELETE RESTRICT` |
| `state` | `PENDING_PAYMENT`, `ACTIVE`, `CANCELED`, `EXPIRED` |
| `price`, `currency`, `billing_period` | **Snapshotted from the plan at purchase** |
| `started_at` | When it became `ACTIVE`. Null while pending |
| `current_period_end` | When the entitlement stops |
| `cancel_at_period_end` | A cancellation the creator has already paid for |
| `canceled_at`, `activated_by`, `note` | |
| `created_at`, `updated_at` | |

`ON DELETE CASCADE` on `account_id` because the test suites truncate `users`, and
a `NO ACTION` foreign key to that table breaks every suite that does.

**One open subscription per account**, as a partial unique index over
`state IN ('PENDING_PAYMENT', 'ACTIVE')`. Two concurrent purchases race on the
index rather than on a read-then-write, and the loser gets a conflict.

### 5.3 Price is snapshotted, limits are not

The subscription row carries the price it was sold at. The limits are read live
from the plan.

The two halves have different failure modes and that is why they are treated
differently. A price that moved under a subscriber is a bill they did not agree
to. A limit that moved under a subscriber is either a gift — the operator raised
what Starter allows, and everybody on Starter gets it, which is what an operator
raising a limit means — or a reduction, which takes effect at their next renewal
in practice because a creator already at the old limit is not asked to withdraw a
campaign; the gate only refuses new submissions.

## 6. Expiry without a scheduled job

A subscription is entitled when `state = 'ACTIVE'` **and** `current_period_end`
is in the future. Both halves, every time, in one query.

There is no sweep marking rows `EXPIRED`, because nothing reads `state` without
also reading the period — the entitlement query, the console list and the
creator's own view all derive what to show from the pair. A job would exist only
to make a column agree with a clock that the readers are already consulting.

The one place the stale row matters is the unique index, which cannot consult a
clock: an `ACTIVE` row whose period has ended would block the same account from
subscribing again. So `Subscriptions.subscribe` closes an expired row to
`EXPIRED` inside its own transaction, immediately before inserting. The row is
retired by the person it was blocking, at the moment it was in the way.

## 7. Module boundaries

The project module may not name the subscription module's internals, and
`ModuleBoundaryTests` checks it. The contract goes in `shared`, following
`PlatformStaff` exactly:

- `shared.access.PublishingEntitlement` — one method, `allowanceOf(accountId)`.
- `shared.access.PublishingAllowance` — a record: is there a subscription, which
  plan, how many campaigns at once, what goal ceiling.
- `subscription.application.PlanEntitlement` implements it.

**The project module counts its own campaigns.** The allowance says "at most
three"; the project module knows which three. Asking the subscription module to
count campaigns would put a query over `projects` in a module that owns no
project rows, which is the boundary this test exists to keep.

What counts against `max_active_campaigns` is a campaign that has left the
creator's hands and not yet finished: `SUBMITTED`, `CHANGES_REQUESTED`,
`APPROVED`, `SCHEDULED`, `LIVE`, `COLLECTING`, `LATE_PLEDGE`. Drafts do not
count, and neither does anything terminal.

## 8. HTTP

| Method and path | Who | What |
|---|---|---|
| `GET /v1/plans` | Anybody | The listed catalogue. Cacheable |
| `GET /v1/me/subscription` | The account | Theirs, or nothing |
| `POST /v1/me/subscription` | The account | Buy a plan |
| `DELETE /v1/me/subscription` | The account | Cancel at period end |
| `GET /v1/admin/plans` | `CONFIGURE_PLATFORM` | Every plan, listed or not |
| `POST /v1/admin/plans` | `CONFIGURE_PLATFORM` | Add one |
| `PATCH /v1/admin/plans/{id}` | `CONFIGURE_PLATFORM` | Change one |
| `GET /v1/admin/subscriptions` | `CONFIGURE_PLATFORM` | Who is on what |
| `POST /v1/admin/subscriptions/{id}/activate` | `CONFIGURE_PLATFORM` | Record that payment arrived |
| `POST /v1/admin/subscriptions/{id}/cancel` | `CONFIGURE_PLATFORM` | End one, with a reason |

Refusals are RFC 9457 problem details, as everything else here is. The one the
frontend acts on is `SUBSCRIPTION_REQUIRED` (403) from `POST /submit`; the limit
refusals are `PLAN_LIMIT_EXCEEDED` (403) and carry which limit and what it is.

Every write under `/v1/admin` is audited: `PLAN_CREATED`, `PLAN_CHANGED`,
`SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_CANCELED`.

## 9. The web

### 9.1 `/[locale]/pricing`

Public, translated into all four of §21.1's languages, server-rendered from
`GET /v1/plans`. A signed-in creator also sees where they stand.

`?from=submit&project={id}` is what a refused submission adds. It changes two
things: a banner at the top saying why they are here, and a return to the
campaign after a plan is chosen. Without it the creator arrives at a price list
with no explanation of what they did to deserve it.

### 9.2 The refusal is a redirect

`ReviewPanel` catches `SUBSCRIPTION_REQUIRED` and navigates to
`/pricing?from=submit&project={id}`. It does not render an alert with a link in
it, because the requirement is a redirect and because an alert offering a link is
a decision the creator has to take twice.

`PLAN_LIMIT_EXCEEDED` does **not** redirect. The creator has a subscription; they
have hit its limit, and the answer may be to withdraw something rather than to
pay more. That is rendered in place, beside the plan's numbers, with the pricing
page one link away.

### 9.3 `/[locale]/admin/plans`

Filed under AD-11, in `otherScreens`, and in the console rail's money group.

§4.11 has sixteen modules and no seventeenth row for this, and
`lib/admin/navigation.ts` states the rule: a module list that disagrees with the
specification about how many modules there are is worse than one that files a
screen under the nearest true heading. AD-11 is "what the platform charges", and
what it charges a creator to publish is the same subject as what it charges a
backer to pledge. `/admin/staff` was filed under AD-04 by the same argument.

The screen does two things: the plan catalogue, and the subscriptions waiting for
payment to be recorded.

## 10. Seeding

Three plans ship in the migration — Starter, Growth, Pro — because a platform
whose gate is live and whose catalogue is empty is a platform nobody can publish
on. They are ordinary rows and an operator edits or unlists them from the console
on the first day.

`created_by` is a problem for a seed: the column is `NOT NULL REFERENCES users`
and a migration has no user. It is therefore nullable, with a comment saying that
null means "shipped with the platform" and a check that it is set on anything the
console writes — enforced in the service, which is the only thing that writes.

## 11. Testing

**Backend**

- `SubscriptionTests`, `SubscriptionPlanTests`, `PlanLimitsTests` — the domain:
  period arithmetic, what "entitled" means at a boundary instant, what a null
  limit means.
- `PlanEntitlementTests` — the allowance an account gets in each state.
- `SubscriptionApiTests` — buy, cancel, buy again after expiry, two purchases
  racing, the pending path, the free path.
- `AdminPlanApiTests` — the console's CRUD, and that `CONFIGURE_PLATFORM` is
  required for every write.
- `ProjectSubmissionEntitlementTests` — the gate: no subscription refuses, an
  active one allows, the campaign-count limit refuses at the boundary and not
  below it, the goal ceiling likewise.

State transitions and limits are on CLAUDE.md's not-optional list. So is money
arithmetic, and every amount here is `BigDecimal` on the backend and a string on
the wire.

**Frontend**

- `PricingPage` / `PlanChooser` — the catalogue renders, the banner appears only
  with `?from=submit`, choosing a plan calls the right endpoint.
- `ReviewPanel` — `SUBSCRIPTION_REQUIRED` navigates; `PLAN_LIMIT_EXCEEDED` does
  not.
- `PlanManager` — the console screen, including its refusal when the reader lacks
  the capability.
- The catalogue test already asserts that all four languages carry every key.

## 12. What this leaves open

- **Card payment.** #60. Until then a paid plan is activated by staff, and §3
  says why that is honest rather than a stub.
- **Renewal.** A subscription ends at `current_period_end` and the creator buys
  again. Automatic renewal needs a stored card, which needs #60.
- **Per-plan fee rates.** A plan that changed §9's platform rate would compete
  with the fee schedule for the same answer. If it is wanted, it belongs in
  `fee_schedules` as a fourth scope, not here.
