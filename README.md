<div align="center">

# IdeaNest

**A reward-based crowdfunding platform.**
Creators publish a project, backers pledge, and money only moves if the goal is met.

[![CI](https://github.com/ismetcahangirov/ideanest/actions/workflows/ci.yml/badge.svg)](https://github.com/ismetcahangirov/ideanest/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-UNLICENSED-lightgrey.svg)](#license)

</div>

---

## What it is

IdeaNest funds creative projects on an **all-or-nothing** model. A creator sets
a goal and a deadline. Backers pledge against reward tiers. If the goal is met
by the deadline, every pledge is collected and the creator is paid. If it is
not, nobody is charged and no fee is taken.

This is not investment. A backer receives a product or an experience, never
equity, interest, or a share of revenue.

**Status:** in development. Nothing here is production-ready yet.

---

## Product surface

| Area | Summary |
|---|---|
| **Discovery** | Fifteen categories, roughly a hundred subcategories, faceted filtering by status, location, goal, amount raised, and completion, with seven sort orders |
| **Campaign** | Story, reward tiers built from atomic items, add-ons, FAQ, updates, comments, and a mandatory risks section |
| **Pledging** | Reward selection, add-ons, shipping destination, and a card stored for collection at close |
| **All-or-nothing** | Success is determined at the deadline and frozen. Later collection failures reduce the payout, never the outcome |
| **Pledge manager** | Post-campaign surveys, address collection, upgrades, shipping rates, tax, backer reports, and tracking |
| **Creator tools** | Live funding figures, referral attribution, backer segmentation, bulk messaging, and a financial summary |
| **Trust and safety** | Pre-launch review, reporting, suspension, identity verification, and a full audit trail |

The complete specification is in [`docs/architecture.md`](docs/architecture.md).

---

## Architecture

```
                    ┌──────────┐  ┌──────────┐  ┌──────────┐
                    │   Web    │  │  Mobile  │  │  Admin   │
                    │ Next.js  │  │   Expo   │  │  React   │
                    └────┬─────┘  └────┬─────┘  └────┬─────┘
                         └─────────────┼─────────────┘
                                       │
                              ┌────────▼────────┐
                              │   API gateway   │
                              │  Spring Boot    │
                              └────────┬────────┘
                                       │
        ┌──────────┬──────────┬────────┼────────┬──────────┬──────────┐
        │   Auth   │ Projects │Discovery│ Pledge │ Payments │  Ledger  │
        └──────────┴──────────┴────────┴────────┴──────────┴──────────┘
                                       │
        ┌──────────────┬───────────────┼───────────────┬──────────────┐
   ┌────▼────┐   ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐  ┌─────▼─────┐
   │Postgres │   │   Redis   │   │  Search   │   │  Object   │  │  Payment  │
   │         │   │ cache/queue│   │           │   │  storage  │  │  provider │
   └─────────┘   └───────────┘   └───────────┘   └───────────┘  └───────────┘
```

A **modular monolith**, not microservices. Pledging, payment, and ledger writes
must share a transaction; splitting them would buy distribution complexity in
exchange for correctness risk in the one place the product cannot afford it.
Module boundaries are explicit so extraction stays cheap when a real scaling
need appears.

### Stack

| Layer | Technology | Why |
|---|---|---|
| Backend | **Java 21, Spring Boot, Gradle** | Mature transaction handling, `BigDecimal` in the standard library, and a deep ecosystem for financial and audit work |
| Database | **PostgreSQL** | ACID, exact numerics, full-text search, and geospatial support in one system |
| Cache, queue | **Redis** | Caching, job queue, distributed locks |
| Web | **Next.js, React 19, TypeScript** | Server rendering, which is not optional for a platform that depends on organic discovery |
| Mobile | **React Native, Expo** | Shares types, validation schemas, and the API client with the web app |
| Styling | **Tailwind 4** on design tokens | One palette, enforced by tests |
| Components | **Storybook 10** | Appearance reviewed in isolation; behaviour covered by tests |

---

## Repository layout

```
ideanest/
├── apps/
│   ├── api/                  Spring Boot service
│   ├── web/                  Next.js — public site and admin console
│   └── mobile/               Expo application               (not yet created)
├── packages/
│   ├── api-client/           Typed API surface, generated from apps/api/openapi.json
│   ├── design-tokens/        Colour, radius, motion — the source of truth
│   └── ui/                   Primitives, layout, motion components
├── docs/
│   ├── architecture.md       Full platform specification
│   ├── ui-kit.md             Colour, surface, typography, components
│   └── motion-system.md      Motion tokens, patterns, budgets
├── CLAUDE.md                 Contribution and workflow rules
└── .github/workflows/
    ├── ci.yml                Typecheck, tests, Storybook build and preview
    └── storybook-preview-cleanup.yml
                              Deletes a preview when its pull request closes
```

The `gh-pages` branch is written by CI only. It holds the published Storybook
builds and is never edited by hand.

---

## Getting started

**Requirements:** Node 22 or newer, pnpm 11 or newer, and — for the backend —
JDK 21 or newer and Docker. Gradle itself is not a requirement; the wrapper
fetches it. Docker is: the backend runs against a real PostgreSQL locally and
in its tests, never an in-memory substitute.

```bash
git clone https://github.com/ismetcahangirov/ideanest.git
cd ideanest
pnpm install

cd apps/api && ./gradlew build      # backend: compile and test
```

### Scripts

| Command | Effect |
|---|---|
| `pnpm storybook` | Component workshop at `localhost:6006` |
| `pnpm dev:web` | Web application at `localhost:3000`. Proxies `/v1` to the backend, so run that too |
| `pnpm typecheck` | Type checking across every package |
| `pnpm test` | Behaviour, accessibility, and colour-discipline tests |
| `pnpm build:storybook` | Static Storybook build, as CI runs it |
| `pnpm build:web` | Production build of the web application |
| `pnpm --filter @ideanest/api-client generate` | Rewrites the typed client from `apps/api/openapi.json` |

The backend is a Gradle project and is not driven through pnpm. Its commands and
conventions are in [`apps/api/README.md`](apps/api/README.md).

**The API contract is two committed files, and neither is regenerated by the
build.** `apps/api/openapi.json` is written by `./gradlew exportOpenApi` and
`packages/api-client/src/schema.ts` by the command above. Both are asserted
current by tests, so a change to a response body shows up in a diff rather than
in a client's runtime — see
[`packages/api-client/README.md`](packages/api-client/README.md).

Start with Storybook. It is the fastest way to understand the visual system,
and `Patterns/Discovery Rail` shows every primitive working together.

### Storybook previews

CI publishes a browsable build for every pull request and comments the link on
it, so a reviewer can look at a component instead of reading a description of
one.

| Build | URL |
|---|---|
| `main` | <https://ismetcahangirov.github.io/ideanest/main/> |
| Pull request | `https://ismetcahangirov.github.io/ideanest/pr-<number>/` |

The directory is removed when the pull request closes. Pull requests from forks
get no preview: their token is read-only by design, and granting an untrusted
branch write access in exchange for a URL is not a worthwhile trade.

---

## Design system

Three rules hold the interface together. Two of them are enforced by tests.

**Colour comes only from tokens.** A hex literal in source fails the build. That
is what makes `packages/design-tokens` trustworthy as the complete palette.

**Lime means urgent, not successful.** A campaign that reached its goal uses
`--success`. Conflating them would tell a backer the opposite of the truth.

**Motion decreases as money gets closer.** Discovery may animate. Checkout must
not — every animation on a payment screen reads as hesitation.

Details in [`docs/ui-kit.md`](docs/ui-kit.md) and
[`docs/motion-system.md`](docs/motion-system.md).

---

## Contributing

Read [`CLAUDE.md`](CLAUDE.md) first. In short:

1. Pull `main` before starting
2. Branch as `<type>/<description>` — never commit to `main`
3. Write commits and pull requests in English, using Conventional Commits
4. Open a pull request, link its issue, and add `type:`, `area:`, and
   `priority:` labels
5. Merge only when CI is green

Work is tracked as [epics with sub-issues](https://github.com/ismetcahangirov/ideanest/issues?q=is%3Aissue+label%3Aepic).
Pick a sub-issue rather than an epic. Issues labelled `status: needs-decision`
are blocked on a product or legal answer — implementing around them wastes the
work, because the answer changes the design.

---

## Known open questions

These are tracked as issues and block real design decisions:

- **Payment provider capabilities.** The all-or-nothing model requires storing a
  card at pledge time and charging it weeks later, without the backer present.
  Card authorisation holds expire long before a campaign closes, so this is the
  only workable approach — and it depends on written confirmation from the
  provider that merchant-initiated transactions are supported.
- **Holding third-party funds.** Whether money sitting between collection and
  payout requires a payment services licence.
- **Merchant of record.** Determines who owes tax on a reward, and therefore how
  the ledger is structured.

---

## License

UNLICENSED. All rights reserved.
