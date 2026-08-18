# @ideanest/web

The Next.js application. App Router, React 19, Tailwind 4, and the primitives
from `@ideanest/ui`.

`@ideanest/ui` has two entry points and the choice is a performance decision, not
a taste one: the root is free, and `@ideanest/ui/motion` pulls in 116 kB of
animation runtime. Import `FadeUp`, `Modal`, `Drawer`, `Popover`, `Tooltip` and
the toast pair from `/motion`; import everything else from the root. A route that
never imports `/motion` never ships `motion` — which is how the checkout stopped
paying for an animation library it does not use. `packages/ui/README.md` has the
measurement.

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
| `IDEANEST_API_ORIGIN` | `http://localhost:8080` | Where `/v1/*` is proxied. Read at build time on the server only — the browser never learns it. The sitemap also reads the service directly through it |
| `IDEANEST_SITE_URL` | `http://localhost:3000` | This application's public origin. **Must be set in any deployed environment** — every `robots.txt` entry, sitemap URL, canonical URL, `og:url`, and absolute social-image URL is written against it. Server-only, and read at build time by the statically rendered pages, so changing it means rebuilding. A value that is set but is not an absolute `http(s)` URL is refused rather than fallen back on |
| `NEXT_PUBLIC_IDEANEST_RUM_SAMPLE_RATE` | `1` | Fraction of sessions whose Core Web Vitals are reported. `0` collects nothing at all. `NEXT_PUBLIC_`, so it is inlined at build time and changing it means rebuilding. See [Real user monitoring](#real-user-monitoring) |
| `IDEANEST_RUM_LOCAL_SINK` | on outside production | The in-memory buffer behind `GET /api/rum`. `next start` runs as production on a laptop too, so set `true` to keep the table there |

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
| `/robots.txt` | **Public.** Crawl directives, and the pointer to the sitemap index (#122) |
| `/sitemap_index.xml` | **Public.** The index over the sitemap segments (#122) |
| `/sitemap/[segment].xml` | **Public.** One sitemap segment — `pages`, `discovery`, `projects-N` (#122) |
| `/api/rum` | **Public**, unauthenticated. The Core Web Vitals collection endpoint (#128) |

There is no route at `/` yet; server-rendered project and discovery pages are
#119. The root segment still carries the site's default metadata and its
`opengraph-image`, both of which every route below inherits, and both of which
work without a page of their own.

Every route above declares its metadata through `src/lib/seo/metadata.ts` — see
[Metadata and social previews](#metadata-and-social-previews).

`/projects/[id]/prelaunch` and `/discover` are the routes that work with no
session at all. `/projects/[id]/back` is the half-way case: its reward list is
`permitAll` and reads through `publicFetch`, so the prices render for a visitor
who has not registered, and only the two mutations need a session. For the pre-launch page the reason is the followers it exists to
collect, who have not registered; for discovery it is that a visitor who has not
registered is the entire audience — requiring a token would mean the front door
could not render. Both read through `publicFetch`, which sends a bearer token
only when one is already in memory and never fetches one.

## Metadata and social previews

Every title, description, canonical URL, and social card is built by
`src/lib/seo/metadata.ts` (#120). No page writes an `openGraph` block of its own:
a page that did would be the page that forgot `og:site_name`, or spelled the
locale differently, or printed the site name into `og:title` where
`og:site_name` already says it — mistakes that render identically in review and
are visible in every shared link forever.

| Module | Holds |
|---|---|
| `lib/seo/metadata.ts` | `publicPageMetadata`, `privatePageMetadata`, `projectPageMetadata`, the canonical rule, word-boundary truncation, and the §6.1 public/private split |
| `lib/seo/metadata-source.ts` | The one server-side, **anonymous** read of a campaign's public projection |
| `lib/seo/metadata-card.tsx` | The Open Graph card, drawn from `@ideanest/design-tokens` |
| `app/opengraph-image.tsx` | The site's card. Static — rendered once by `next build` |
| `app/discover/opengraph-image.tsx` | The same card for `/discover`, and it is **not** redundant (see below) |
| `app/projects/[id]/prelaunch/opengraph-image.tsx` | A campaign's card, per request |

**Two shapes, because there are two kinds of page.** A public page gets a
canonical, a full Open Graph block, and a large X card. A private one —
`/projects/[id]/back`, `/projects/new`, `/settings/sessions`, every editor tab —
gets `noindex, nofollow`, no canonical, and no card at all. `nofollow` as well as
`noindex` on all of them: they are shells around client boundaries, so the markup
a crawler receives has no links in it, and `follow` would be a promise of crawl
budget spent finding nothing.

**One canonical for the whole of discovery, and every query string is dropped.**
The filters live in the query string and the feed they select is fetched in the
browser, so `/discover`, `/discover?category=games`, and
`/discover?utm_source=news&page=7` are all served the *same document* — one
canonical is what actually happened rather than a simplification. Emitting a
per-filter canonical would mean reading `searchParams` inside `generateMetadata`,
which was measured to move `/discover` from `○ (Static)` to `ƒ (Dynamic)` in the
build output. A per-request render of the front door is not worth a tag.

**A campaign's card is its own cover photograph when it has one.** §5.3 requires
a cover of at least 1024×576, and an unfurler holding a real photograph should
not be shown typography instead. With no cover — which, until the media pipeline
of §13.1 exists, is most campaigns — the `opengraph-image` route draws the title
and summary onto the site's card. It deliberately embeds no bitmap: Satori
decodes PNG, JPEG, and SVG, while §13.1 serves AVIF first.

**Nothing about a campaign is printed until it is confirmed public.** The
projection is read anonymously, without credentials, because what a link preview
may show is exactly what an anonymous request answers — a card built from an
authenticated read would say more than its audience may see. `PRELAUNCH`,
`SCHEDULED`, `LIVE`, and the five post-funding states are public;
`docs/architecture.md` §6.1's other seven are not, and a campaign in one of them
gets the private shape with no title, no summary, and no cover. A service that
does not answer is treated the same way: a brief `noindex` is re-crawled and
recovers, an indexed draft does not.

**`app/discover/opengraph-image.tsx` is load-bearing.** Next merges a file-based
Open Graph image into the metadata of the segment the file sits in, and a child
segment that declares its own `openGraph` block replaces its parent's resolved one
wholesale. `/discover` declares one, so without a card in its own directory it
would have no `og:image` at all. Any future public route with its own title needs
the same file, or an explicit image.

**Not done here.** `sitemap.xml`, `robots.txt`, and the finer question of which
*public* pages should be indexed are #122's (`lib/seo/indexability.ts`).
`isPubliclyVisible` is the floor both rest on: nothing it refuses may be indexed
or described, whatever that module decides on top.

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

## Sitemaps and robots

`src/lib/seo/` holds one decision — **may this campaign be indexed** — and the
files that act on it.

### The indexability rule

`src/lib/seo/indexability.ts` answers for **all sixteen** project states of
`docs/architecture.md` §6.1, one of three ways. The table is a
`Record<ProjectState, …>`, so a seventeenth state is a type error rather than a
campaign that quietly defaults to indexable, and a state this build has never
heard of is treated as **not public** — it fails closed.

| Answer | States | Why |
|---|---|---|
| `NOT_PUBLIC` | `DRAFT`, `SUBMITTED`, `CHANGES_REQUESTED`, `REJECTED`, `APPROVED`, `SCHEDULED`, `SUSPENDED` | §4.3: discovery never returns these under any filter or sort. The service states the same seven independently as `DiscoveryStatus.HIDDEN_STATES` |
| `PUBLIC_NOT_INDEXABLE` | `PRELAUNCH`, `CANCELED` | Reachable, deliberately not indexed. A pre-launch page is an **unmoderated** teaser — `DRAFT → PRELAUNCH` passes no review — on an id-based URL that stops existing when the campaign opens. A cancelled campaign stays reachable for the backers who have the link, but a permanent search result for a withdrawn campaign is a dead end outranking the live ones beside it |
| `INDEXABLE` | `LIVE`, `LATE_PLEDGE`, `SUCCESSFUL`, `COLLECTING`, `FULFILLING`, `COMPLETED`, `UNSUCCESSFUL` | Public, moderated, and stable — §4.4's project page is the page a backer is looking for |

**One predicate, both surfaces.** `isIndexableProjectState` is what the sitemap
filters on, and `projectPageRobots` is the same decision shaped as a `robots`
meta directive for the project page itself. Nothing renders the second yet —
#119 owns the page and #120 owns its metadata — and it lives here so that the
day either lands, the page and the sitemap cannot disagree. A page that says
`noindex` while the sitemap advertises it is a page a crawler is invited to and
then turned away from.

`follow` stays true when `index` is false: a withdrawn campaign still links to
its creator and its category, and those pages are indexable.

### Segmentation

Next's `generateSitemaps` maps `src/app/sitemap.ts` onto `/sitemap/{id}.xml`, one
file per segment, and `src/app/sitemap_index.xml/route.ts` is the
`<sitemapindex>` over them — Next writes no index of its own, and taking
`/sitemap.xml` for a route handler would collide with the metadata route.
`robots.txt` points at the index and not at the segments, so the shard count can
change without a redeployment.

| Segment | Holds |
|---|---|
| `pages` | Static pages |
| `discovery` | The unfiltered feed |
| `projects-N` | Indexable campaigns, 45,000 per shard |

**45,000 and not 50,000.** The limit is 50,000 URLs and 50 MB uncompressed, and a
file that breaches either is rejected whole rather than truncated. There is
always at least a `projects-0`, empty if need be: an index referencing a segment
that 404s is an error in Search Console, and a segment whose URL appears and
disappears with the campaign count is one nobody can submit.

Segmenting by content type is not only about size. The three kinds change at
completely different rates, and a crawler can skip a segment whose contents have
not moved — which is impossible to say about a file mixing a campaign that
changes hourly with a page that changes yearly.

### What is claimed about each URL

`lastModified` comes from the campaign's own timestamps: `launchedAt`, and
`deadline` **once it has passed**, because the page changed at that moment from
one taking pledges to one that succeeded or did not. A deadline in the future is
a promise rather than a modification. A campaign with neither gets **no**
`<lastmod>` — `new Date()` would tell a crawler that every page changed on every
crawl, which is how the field comes to be ignored for the whole file. The public
listing carries no `updatedAt`; if one is ever added, this is the one function
that reads it.

`changeFrequency` is a statement about the content and is true: `daily` for a
live campaign, whose amount raised and backer count move every day; `monthly`
through fulfilment, which posts updates over weeks; `yearly` for a page that is
finished.

**There is no `priority`, anywhere, deliberately.** It asks for relative
importance within the site on a scale with no defined meaning, Google states that
it ignores the field, and there is no number here that would be true if it did
not. A field invented to fill a column is a field a reviewer has to pretend to
check.

### What robots.txt refuses

`Allow: /` first and the exceptions after, rather than the reverse — a robots.txt
built the other way round quietly stops indexing every page added after it was
written. The exception list is `PRIVATE_PATH_PREFIXES`, beside the predicate, and
it covers the pledge flow, the campaign editor, the pre-launch teaser, the
account, the creator dashboard, the pledge manager, administration, and the
proxied `/v1` API. **The dashboard, pledge manager and administration are not
built**, and are disallowed anyway: a private surface has to be disallowed on the
deploy that introduces it, and a robots.txt updated afterwards is updated too
late.

`Disallow: /discover?` blocks **every filtered permutation of the feed**. §4.3's
filters are query parameters, they compose, and several are comma-separated
lists, so the number of URLs describing one set of campaigns is combinatorial and
each is crawl budget spent on a page that already exists. That includes `?q=`,
where the URL set is whatever anybody types. `/discover` itself, with no query,
is allowed and is in the sitemap.

### Named gaps

- **The URLs the sitemap emits for `/` and `/projects/{creatorSlug}/{projectSlug}`
  do not resolve yet.** Neither route exists in this application; both are #119.
  The sitemap encodes the platform's public URL contract (§4.4, §10.2) rather
  than an inventory of the routes that happen to be built, because a sitemap that
  has to be rewritten by whoever ships the page is one that gets shipped without
  it. Until #119 lands, those entries point at 404s.
- **No category landing pages.** §4.3's fifteen categories and hundred
  subcategories would make good landing pages, but the only URL reaching one is
  `/discover?category=games`, which `Disallow: /discover?` blocks — and a sitemap
  must never advertise a URL robots.txt blocks. A path-based category route is
  what makes them indexable, and it belongs with #119.
- **The campaign list is walked, not counted.** There is no endpoint that returns
  a count or a bare list of public project URLs, so the sitemap pages through
  `GET /v1/discover` at `limit=100` — bounded at 500 pages, or 50,000 campaigns —
  and memoises the walk for fifteen minutes. Past that bound the walk is the
  wrong shape and the service needs an endpoint built for it.
- **The sitemap reads the service directly rather than through `publicFetch`.**
  `lib/api/client.ts` issues a same-origin *relative* request, which is what
  makes the `SameSite=Strict` refresh cookie work in a browser and what makes it
  unresolvable in Node, and it attaches an in-memory access token that must never
  ride on a crawl-facing read. The sitemap resolves `/v1/discover` against
  `IDEANEST_API_ORIGIN` — the same variable the proxy uses — and imports its
  response types from `lib/discovery/api.ts` rather than restating them.

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

## Real user monitoring

`src/lib/rum/` measures LCP, INP, CLS, TTFB and FCP on real devices and posts
them to `POST /api/rum` (#128). `docs/observability/real-user-monitoring.md` is
the full account — the thresholds, the sampling reasoning, the privacy rules,
what a production sink would have to be, and the named gaps. The short version:

**Field, not lab.** `apps/web/performance/` measures Core Web Vitals in
Lighthouse on a CI runner; this measures them on the devices people actually
use. Both use Google's published good/needs-improvement boundaries, and
`src/lib/rum/metrics.test.ts` reads `performance/summarise-lighthouse.mjs` and
fails if the two stop agreeing. INP appears only here — a headless load performs
no interaction, so no lab tool can report one.

**p75, nearest rank**, because that is how Core Web Vitals is defined and
because an interpolated percentile is a headline figure no session experienced.

**Nothing that identifies a person.** No URL — a Next route pattern from a fixed
whitelist, so a campaign identifier and a search term cannot leave the browser.
No query string, no account, no user agent, no stored IP address, and no field a
free string can enter. Two locks: the schema refuses unknown keys and validates
every value against a closed vocabulary, and §17.4's shape rules then run over
what is left.

**Session-consistent sampling**, defaulting to every session, because a p75 over
a few hundred samples moves by more between two ordinary days than a regression
would move it — and there is no production traffic to sample down from yet. A
coin flip per metric would rank metrics rather than visits and produce something
with the shape of a p75 that is not one.

**`sendBeacon`, falling back to `fetch(…, { keepalive: true })`.** The metrics
worth having are final only as the page goes away, which is exactly when a
browser cancels requests. Reporting a metric does nothing but push onto an
array; the send happens in an idle callback, or synchronously on `pagehide`.
`<WebVitals />` renders `null`, so there is no element, no motion, and nothing
that could shift the layout it is measuring.

**It joins the server's traces.** `instrumentation-client.ts` puts the session's
W3C `traceparent` on every same-origin `/v1` request, using the identifiers
`docs/architecture.md` §18.1 already defines, so a slow field session and its
server spans share a `traceId`. It never overwrites a header a caller set, so
the day `lib/api/client.ts` does this itself, this stops.

**No analytics vendor is introduced.** Accepted samples become one JSON line per
sample on stdout, beside the application's other lines. §14.2 lists product
analytics as a choice not yet made, and it stays not yet made.

```bash
pnpm --filter @ideanest/web dev
curl http://localhost:3000/api/rum        # the p75 table, in development
```

## Styling

`src/app/globals.css` imports `@ideanest/ui/styles.css` and nothing else. That
one import brings Tailwind, the token file, and the theme bridge, so this
application defines no colour of its own — see `docs/ui-kit.md` §10.1, where a
test fails the build on a colour literal.

### Fonts

`src/app/layout.tsx` loads Inter through `next/font/google` and binds it to
`--font-inter` on `<html>`; the theme bridge in `@ideanest/ui/styles.css` reads
that variable for `--font-sans` and `--font-display`. Next self-hosts the files
out of `/_next/static/media`, so there is no request to a third origin and no
blocking round trip before the browser learns which `.woff2` to fetch.

**Only `latin` and `latin-ext` are declared, and both are needed.** Google's
`latin` cut carries `ı`, `ö`, `ü` and `ç`, but `ə` (U+0259), `Ə`, `ğ`, `Ğ`, `ş`,
`Ş` and `İ` live in `U+0100-02BA`, which only `latin-ext` declares. A missing `ə`
is a broken product in this market, so `src/app/font-subsets.test.ts` fails if
the list stops covering those code points, and fails again if one of the
`cyrillic`, `greek` or `vietnamese` cuts is added — each one is another file
preloaded against the largest contentful paint.

Those two are the **only** preloads on the page. They earn it: every glyph above
the fold is set in this family. `adjustFontFallback` is left at its default, so
Next synthesises a metric-matched local fallback and the `display: 'swap'` does
not move the layout — which is what keeps cumulative layout shift inside the
0.05 `docs/motion-system.md` §8 asks for.

Weight is the full variable axis rather than the four weights `docs/ui-kit.md`
§5.3 names. Google serves Inter v20 as a variable file either way: asking for
`wght@400;500;600;700` returns the same two subset files 380 bytes smaller, so
pinning would buy nothing and would break the first `font-bold` somebody adds.

`--font-display` still asks for General Sans first, which `docs/ui-kit.md` §5.1
makes the first choice for display and headings. It is a Fontshare licence and a
vendored binary rather than a build-time download, so until somebody makes that
call the stack resolves to Inter — the substitution §5.1 names itself.
