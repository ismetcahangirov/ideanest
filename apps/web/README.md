# @ideanest/web

The Next.js application. App Router, React 19, Tailwind 4, and the primitives
from `@ideanest/ui`.

## Running it

```bash
pnpm --filter @ideanest/web dev        # http://localhost:3000
```

The service has to be running too, because every `/v1` request is proxied to it:

```bash
cd apps/api && ./gradlew bootRun       # http://localhost:8080
```

| Variable | Default | Meaning |
|---|---|---|
| `IDEANEST_API_ORIGIN` | `http://localhost:8080` | Where `/v1/*` is proxied. Read at build time on the server only — the browser never learns it |

### Why the API is proxied rather than called directly

The refresh cookie is `HttpOnly`, `SameSite=Strict`, scoped to `/v1/auth`. A
browser will not attach it to a request aimed at a different origin, which is
the entire point of the setting, and the service declares no CORS policy so a
cross-origin call would not survive its preflight either. Routing `/v1` through
this application is what makes the pair same-origin, and therefore what makes
the browser half of the auth flow work at all.

## Scripts

| Script | What it does |
|---|---|
| `dev` | Development server |
| `build` / `start` | Production build and server |
| `typecheck` | `tsc --noEmit` — runs in CI |
| `test` | Vitest, behaviour and accessibility — runs in CI |

## Routes

| Route | Issue |
|---|---|
| `/settings/sessions` | Session management (#27) |
| `/projects/new` | Name a campaign and create the draft (#33) |
| `/projects/[id]/edit` | Redirects to the first tab (#33) |
| `/projects/[id]/edit/basics` | Title, summary, category, goal, duration, cover (#33) |
| `/projects/[id]/edit/story` | Rich text story, risks, and version history (#35) |
| `/projects/[id]/edit/prelaunch` | Open the pre-launch page, share the link, see who is waiting (#39) |
| `/projects/[id]/prelaunch` | **Public.** The pre-launch page itself, and the reminder signup (#39) |
| `/projects/[id]/back` | Reward selection, add-ons, destination, and confirmation (#54) |
| `/discover` | **Public.** The filter rail, sort, chips, and the cursor-paginated feed (#45) |

There is no route at `/` yet; server-rendered project and discovery pages are
#119.

`/projects/[id]/prelaunch` and `/discover` are the routes that work with no
session at all. `/projects/[id]/back` is the half-way case: its reward list is
`permitAll` and reads through `publicFetch`, so the prices render for a visitor
who has not registered, and only the two mutations need a session. For the pre-launch page the reason is the followers it exists to
collect, who have not registered; for discovery it is that a visitor who has not
registered is the entire audience — requiring a token would mean the front door
could not render. Both read through `publicFetch`, which sends a bearer token
only when one is already in memory and never fetches one. Rich link previews
need a server-rendered public projection, which is the discovery epic's (#119).

## Discovery

`/discover` is a client route and its state is the query string. Every filter
and the sort are parameters the service itself defines, so a filtered feed is a
shareable URL (D-12), survives a reload, and comes back off the back button. The
**cursor is deliberately not in the URL**: a shared link should open a fresh
first page rather than page seven of somebody else's scroll, and a cursor is
fingerprinted to the query it was issued for, so one in a link would be answered
`400 DISCOVERY_CURSOR_MISMATCH` the moment the recipient touched a filter.

| Module | Holds |
|---|---|
| `lib/discovery/vocabulary.ts` | The closed vocabularies, copied from `az.ideanest.discovery.domain` |
| `lib/discovery/filters.ts` | The filter state, the URL it serialises to, and the active-chip set |
| `lib/discovery/bounds.ts` | The custom money range, checked with `decimal.js` |
| `lib/discovery/api.ts` | `GET /v1/discover` and `/v1/discover/facets` |
| `lib/discovery/useDiscoveryFeed.ts` | D-04's paging, and the one-request-per-cursor guard |
| `lib/discovery/useDiscoveryFacets.ts` | D-10's live counts |
| `lib/discovery/emptiness.ts` | Which filter emptied the feed |

**Filters the service refuses today are not rendered.** Location (country, city,
proximity), `showOnly=saved`, `showOnly=recommended`, `showOnly=featured`, free
text, `sort=relevance` and `sort=near_me` are all representable on the service's
query object and answered with `400 DISCOVERY_OPTION_UNSUPPORTED` naming the
issue that owns them (#43, #44, #47, #48). A control that cannot work is a
promise the interface breaks the first time somebody uses it, so they are absent
rather than disabled.

**Infinite scroll is the enhancement, not the mechanism.** There is a real
"Show more projects" button and an `IntersectionObserver` that presses it. The
other way round is how a feed becomes unreachable by keyboard and by screen
reader, because neither produces a scroll that intersects anything.

## The checkout

`/projects/[id]/back` is the pledge flow of `docs/architecture.md` §4.5 — PL-01
to PL-08 and PL-12. The segment is `back` because that is the reader's word for
what they are doing; `checkout` is ours for the machinery, and it borrows a
shopping cart's model, which is wrong here in a way that matters: nothing is
bought and nothing is charged.

**Three steps, one route, and no query string.** Unlike `/discover`, the state
here is React state. A half-made pledge is not a thing to link to, and a
reservation lives five minutes — a back button that could re-enter the review
step would routinely land on a hold that has gone. The one parameter the route
reads is PL-15's `?token=`, repeatable, which unlocks secret tiers.

| Module | Holds |
|---|---|
| `lib/pledges/api.ts` | The public reward list, the draft, the read, and the confirm |
| `lib/pledges/quote.ts` | PL-06's total, mirroring `PledgeQuote` in `pledge/domain` |
| `lib/pledges/idempotency.ts` | What "the same intent" means, and when a key is retired |
| `lib/pledges/failure.ts` | Each contract refusal, with the recovery that belongs to it |
| `components/checkout/useCheckout.ts` | The selection, the two requests, and the phase |
| `components/checkout/useReservationClock.ts` | PL-13's five minutes, counted down and not animated |

**Two totals, and only one of them is true.** The client quotes the selection
with `decimal.js` so the figure moves as the backer chooses. The moment a draft
comes back, `PledgeResponse.amounts` replaces it outright — the two are never
merged and never shown together. The server prices the row it is about to
reserve, inside the transaction that reserves it; the client is pricing a list it
fetched some seconds ago.

**The idempotency key belongs to an intent, not to an attempt.** A key is issued
per request body, so a retry after a dropped connection sends the same key and is
answered with the same pledge rather than creating a second one. It is retired in
exactly two cases: a reservation that expired — where the body is identical but
the intent is new, and replaying would hand back the draft that just expired —
and a key the service has told us is spent. Going back to change the selection
does **not** retire it, because a backer who changes nothing should get their
existing reservation and its existing clock.

**A collision with your own first attempt is waited out, not reported.** `409
IDEMPOTENT_REQUEST_IN_PROGRESS` is what a double-click produces: the first
request still holds the claim on the key, and the second is told to ask again.
The backer can do nothing about it and their pledge is already being made, so
`useCheckout` waits the `Retry-After` the response carries and sends the **same
key** again — capped at five seconds a wait and three retries, after which the
message says what happened and offers the button. A fresh key on that retry
would be the second pledge this whole mechanism exists to prevent.

| Refusal | What the checkout does |
|---|---|
| `IDEMPOTENT_REQUEST_IN_PROGRESS` | Waits `Retry-After` and re-sends, same key, bounded |
| `IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_INVALID` | Says it is a fault in this page. Neither can happen while `idempotency.ts` sends a `crypto.randomUUID()`, so reaching one is a bug report and not a state a backer can recover from |
| `PLEDGE_MODIFIED` | Offers a new reservation. Usually §8.4's sweep expiring the draft as it was confirmed — the service will not claim a cause it inferred rather than observed, and neither does the wording |

**The confirmation reads the pledge rather than asserting facts about it.**
`cardVerified` and `paymentMethodId` come back on every `PledgeResponse`, and
the "what happens now" sentences are written from them. Both say the same thing
today as the hard-coded prose they replaced — nothing charged, no method
collected — and #55 is precisely the change that would have made that prose a
lie nobody was told to go and fix.

**There is no card form, and adding one would be a defect.** Card entry and 3-D
Secure are #55, blocked on #60 (`status: needs-decision`), so there is no
provider, no hosted field and nothing to tokenise with. `§17.2` targets SAQ A,
which holds only while card data never touches our servers — and a field that
looks like it takes a card teaches somebody that this is where a card goes on
this site. The payment step is an honest note saying nothing is collected, and
`paymentMethodId` is sent as null.

**Confirmation is a commitment, not a payment.** §9.2 collects nothing until the
campaign succeeds, and this build has not even taken a card, so the confirmation
screen says both in as many words.

**Tax is `0.00` and the line is not printed.** `TaxPolicy` holds the zero
deliberately until #78. A permanent "Tax 0.00" row invites the one question the
interface cannot answer; the line appears by itself the day the policy stops
answering zero.

## The campaign editor

The editor is one shell (`src/components/campaign-editor/EditorShell.tsx`) and
one tab per route. `src/components/campaign-editor/tabs.ts` is the only place a
tab is declared: the shell renders that list, and a section whose route does not
exist yet is a **disabled tab rather than a stub page**. A stub cannot be told
apart from a broken page, and the file it would need belongs to the issue that
builds it. Adding a section is a row in `tabs.ts` plus the route it points at.

Every section in `EDITOR_TABS` now has its route, so nothing is currently marked
unavailable. The mechanism stays because `docs/architecture.md` §4.6 describes
three sections that are still unbuilt — people, account, and promotion — and each
of them will be a disabled tab before it is a page.

### The pre-launch tab

It edits the campaign's `title`, `blurb`, and `coverImage` through the same
autosave path the basics tab uses, and not through fields of its own. A dedicated
pre-launch headline would let a creator promise one thing on the page people
follow and something else on the campaign it becomes, and the follower signed up
for the first.

Opening the page is behind a dialog rather than a switch: it publishes the
campaign at a public link, and `docs/architecture.md` §6.1 has no
`PRELAUNCH → DRAFT` edge, so there is no undo to offer afterwards.

### The review tab

`ReviewPanel` reads `GET /v1/projects/{id}/checklist` and renders three things:
the itemised completeness checklist, the last moderation decision, and the submit
control.

**The server is the authority.** The checklist is read when the tab opens;
`POST /submit` re-checks the same rules server-side with the same class. When it
refuses with `PROJECT_NOT_SUBMITTABLE`, the requirements *it* named replace what
the screen was showing — a field may have been emptied by a collaborator since, or
the deployment may enforce a rule this build has never heard of. There is
deliberately no client-side copy of §5.3 here; unlike the basics tab, nothing on
this screen is being typed, so there is nothing an immediate local answer would
buy that a second disagreeing implementation would not cost.

**Blocking and advisory never share a presentation.** Two headed groups, two icon
shapes, and every row states "Done", "Required, not done", or "Recommended, not
done" in text. The score is a sentence with counts — `83% complete. 10 of 10
required items done, 0 of 4 recommended.` — and the bar beside it is
`aria-hidden`, because it is a picture of a number the sentence already carries.

### Autosave

There is no save button below the basics tab. `useAutosave` debounces, keeps a
single request in flight, and only clears its pending patch on a **successful**
response — so a retry after a failure sends the same body rather than an empty
one, and nothing typed is lost. `SaveStatus` reports saving, saved, or not saved,
and announces only the outcomes.

### What the basics tab cannot do yet

| Missing | Why |
|---|---|
| Location | `projects` has no `location_id` and there is no geocoding service. `docs/architecture.md` §7.2 holds both for the discovery epic |
| Uploading a cover image | There is no media table and no uploader. The cover is a URL plus the width and height read in the browser; the media pipeline (§13) replaces all three |
| Video | Same media pipeline, and no client-side equivalent of reading an image's intrinsic size |
| Changing the category | Needs `GET /v1/categories`, which no sub-issue of the editor epic owns. The field degrades to a notice and everything else still saves |

## How authentication works here

The access token lives in a module variable in `src/lib/api/access-token.ts` and
nowhere else. It is a fifteen-minute bearer credential — `localStorage` would
hand it to any script that ever runs on the page and outlive the tab for no
benefit. The durable half of the session is the refresh cookie, which script
cannot read by design.

`authorizedFetch` attaches the token, and on a `401` refreshes once and retries
once. Refresh is single-flight: refresh tokens rotate on use and a reused one is
treated as stolen, so two concurrent refreshes would end the session family
between them.

**The two helpers cache differently, and neither serves a stale body.**
`authorizedFetch` sends `cache: 'no-store'`: an account read carries no validator
to revalidate against, so storing the response buys nothing and a stale device
list on a security screen is the one thing that page must never show.
`publicFetch` sends `cache: 'no-cache'`, which still forces a revalidation on
every single request but lets that revalidation be conditional. Public reads
carry `ETag` and `Cache-Control` per `docs/architecture.md` §10.3 — the reward
list is `private, no-cache` — so an unchanged list is answered `304` with no body
instead of re-sending every tier, item and shipping rule on each poll of a live
stock count. `no-store` here would make that `304` unreachable, which is what
#200 fixed.

## Styling

`src/app/globals.css` imports `@ideanest/ui/styles.css` and nothing else. That
one import brings Tailwind, the token file, and the theme bridge, so this
application defines no colour of its own — see `docs/ui-kit.md` §10.1, where a
test fails the build on a colour literal.
