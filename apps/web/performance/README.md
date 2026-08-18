# Performance budgets

Two things live here, and only one of them can fail a pull request.

| Layer | What it measures | Blocking |
|---|---|---|
| **First Load JS budgets** | The JavaScript each route makes a browser download before it is interactive | **Yes** |
| **Lab Core Web Vitals** | LCP, CLS and TBT from a headless Lighthouse run against the built app | No — advisory |

That split is the whole design. A bundle size is a property of the build: the
same commit produces the same bytes on every machine, so a failure is always
somebody's change and never the runner's mood. A lab Core Web Vitals number is
a measurement of a shared CI machine under load, and it moves between two runs
of identical code by more than most regressions worth catching would move it.
Gate on the first, report the second.

The size of that noise is not a guess. Three Lighthouse runs against
`/projects/new`, same build, same machine, nothing else running, measured while
this was written:

| Run | LCP | TBT |
|---|---:|---:|
| 1 | 1822 ms | 240 ms |
| 2 | 1966 ms | 161 ms |
| 3 | 2795 ms | 433 ms |

Both metrics cross their own good/needs-improvement boundary inside that
spread — LCP's at 2500 ms, TBT's at 200 ms — on a quiet laptop. A shared runner
is worse. A gate placed anywhere in that range fails pull requests that changed
nothing, and the first response to a check that fails at random is to delete it,
which would take the bundle budget beside it out with the bathwater.

The alternative was to gate on both with thresholds loose enough to absorb the
noise — but a threshold loose enough never to fire falsely is also loose enough
never to fire, and it costs six Lighthouse runs a pull request to say nothing.
Reporting is the honest version of the same information.

---

## What "regresses" means

**An absolute ceiling per route, committed to `budgets.json`.** Not a
comparison against `main`.

Comparing against `main` catches slow creep that a ceiling lets through — a
route can gain two kilobytes a week for a year and never break a ceiling that
was set with a year of headroom. It is the better signal. It is also the one
that needs a stored baseline per branch, a fetch of `main`'s build, and an
answer for the first pull request after a force-push, and none of that works on
a fork's read-only token.

The ceiling's real cost is different and worth naming: **a ceiling that only
ever goes up stops being a budget and becomes a record of the worst the page
has ever been.** The check refuses that. It fails when a route drops more than
`maxSlackPercent` below its ceiling, so deleting code obliges you to bank the
saving in the same pull request. The ratchet turns both ways, and the creep a
`main` comparison would have caught shows up instead as a budget file that
somebody had to keep editing upward in the open.

---

## The budgets

`budgets.json`:

| Key | Meaning |
|---|---|
| `routes` | Ceiling in KiB of uncompressed First Load JS, per route |
| `sharedFirstLoadKib` | Ceiling for the chunks *every* route loads |
| `maxSlackPercent` | How far under its ceiling a route may sit before the budget is called stale |

**Where the numbers came from: the build, plus five per cent.** They are not
aspirations and the repository's specification names none — `docs/architecture.md`
sets latency targets for the API (§20.4) and says nothing about the client
bundle. So each ceiling is `ceil(measured × 1.05)`, measured on the first build
after the budgets were introduced. Five per cent is roughly one component's
worth of headroom: enough that an ordinary feature lands without a budget edit,
small enough that a new dependency does not.

`sharedFirstLoadKib` is not one of the routes. It is the total of the chunks
present in every route's first load, and it exists because a dependency landing
in the shared graph breaks all twelve route budgets at once without any of them
saying why. The shared line names the cause.

**Sizes are uncompressed**, which is the unit `next build` records. Transfer
size is the number a user actually pays, and it would be the better budget if
it were stable — but gzip output shifts by a handful of bytes between zlib
versions, so a Node upgrade would move every budget at once, and the first fix
anybody reaches for when every budget moves is to widen every budget.

---

## Changing a budget

Raising a ceiling is a normal thing to do and is not a failure of process. It
is a change to a reviewed file, which is the entire point — the diff says how
much heavier the route got and the pull request says what bought it.

```bash
pnpm --filter @ideanest/web build
node apps/web/performance/check-first-load-js.mjs
```

(`next build` rewrites `apps/web/next-env.d.ts` to point at the production type
declarations rather than the development ones. That is Next's doing, not this
check's; discard it before committing.)

The failure names the route, the overage, and — for a stale budget — the number
to write. Edit `budgets.json`, run it again, and put the reason in the commit
body. A budget raised without a sentence explaining what the kilobytes bought
is the thing this file exists to prevent.

A new route fails the check until it has a budget, and a budget for a route
that no longer exists fails it too. Neither is optional: the first stops a
heavy page arriving unmeasured, the second stops the file rotting into a list
of pages nobody can find.

---

## Lab Core Web Vitals

`summarise-lighthouse.mjs` reduces a directory of Lighthouse JSON reports to
one table in the job summary and always exits zero.

Three runs per route, median per metric. Thresholds are Google's published
good / needs-improvement boundaries rather than anything chosen here, so
"amber" in the table means what it means in Search Console:

| Metric | Good | Poor above |
|---|---|---|
| LCP | ≤ 2500 ms | 4000 ms |
| CLS | ≤ 0.1 | 0.25 |
| TBT | ≤ 200 ms | 600 ms |
| FCP | ≤ 1800 ms | 3000 ms |

**INP is not in that table and cannot be.** It is defined over real user
interactions and a headless page load performs none, so no lab tool reports it.
Total Blocking Time is the proxy Google recommends in its place, and it is
labelled as a proxy in the output rather than quietly relabelled INP.

The runs use Lighthouse's default mobile emulation — 4× CPU slowdown and a
throttled network. Desktop numbers would look considerably better and would
describe considerably fewer of the people who use the site.

---

## Cost

About four minutes of runner time per pull request, in a job that runs
alongside the existing ones rather than inside them:

| Step | Roughly |
|---|---|
| Install | 40 s |
| `next build` | 60 s — the web application was not built in CI before this job existed |
| Budget check | under a second |
| Six Lighthouse runs, two routes | 2 min |

The blocking half is the first three rows. If the Lighthouse half ever stops
being worth two minutes, delete those steps: nothing depends on them and the
gate keeps working.
