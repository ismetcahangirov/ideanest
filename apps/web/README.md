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

There is no route at `/` yet; server-rendered project and discovery pages are
#119.

## The campaign editor

The editor is one shell (`src/components/campaign-editor/EditorShell.tsx`) and
one tab per route. `src/components/campaign-editor/tabs.ts` is the only place a
tab is declared: the shell renders that list, and a section whose route does not
exist yet is a **disabled tab rather than a stub page**. A stub cannot be told
apart from a broken page, and the file it would need belongs to the issue that
builds it. Adding a section is a row in `tabs.ts` plus the route it points at.

Rewards (#34), story (#35), pre-launch (#39), and review (#37) are the tabs still
marked unavailable.

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

## Styling

`src/app/globals.css` imports `@ideanest/ui/styles.css` and nothing else. That
one import brings Tailwind, the token file, and the theme bridge, so this
application defines no colour of its own — see `docs/ui-kit.md` §10.1, where a
test fails the build on a colour literal.
