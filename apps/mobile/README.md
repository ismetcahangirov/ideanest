# `@ideanest/mobile`

The React Native application — `docs/architecture.md` §14.3.

```bash
pnpm --filter @ideanest/mobile typecheck
pnpm --filter @ideanest/mobile test
pnpm --filter @ideanest/mobile start   # a development build, not Expo Go
```

## What it is

| Concern | Choice |
|---|---|
| Toolchain | **Expo SDK 57**, React Native 0.86, React 19.2 |
| Navigation | **Expo Router**, file-based, under `src/app/` |
| Styling | `StyleSheet` over `@ideanest/design-tokens` — see below |
| Server state | **TanStack Query 5**, persisted to MMKV |
| Storage | **MMKV** for the cache, the platform keychain for the session |
| Lists | **FlashList** |
| Animation | **Reanimated 4** |
| Push | **Expo notifications** |
| Tests | **jest-expo** — the one package in this repository not on vitest |

## Where it differs from §14.3, and why

**Expo SDK 57 and React Native 0.86, not SDK 52 and 0.76.** §14.3 was written when
52 was current. 52 reached end of life in 2025, its React is 18, and the New
Architecture it describes as a choice is no longer optional. Starting a new
application on it would be starting it two years behind. `docs/architecture.md`
carries the same note.

**No NativeWind.** §14.3 names NativeWind 4, which drives Tailwind 3. This
repository is on Tailwind 4 everywhere — `apps/web` and `packages/ui` both — and
NativeWind's Tailwind 4 release is `5.0.0-preview`. The two available options
were a second, older Tailwind major with its own config dialect living beside
the current one, or a preview dependency underneath every screen in a new
application.

Neither buys anything `StyleSheet` over the tokens does not: the values are
identical either way, and the class names would be a second spelling of them
rather than a second source. `src/theme/index.ts` carries the argument in full,
and `src/theme/theme.test.ts` enforces the part that actually matters — **no
colour anywhere in `src/` that is not a token**, which is the same guard
`packages/ui` runs over its own source.

Revisit when `nativewind@5` is stable. The revisit is cheap because the screens
import the tokens rather than class names.

**Jest, not vitest.** Every other workspace runs vitest and that is still the
right default. React Native ships untranspiled source with Flow annotations, and
the transform that strips them is `babel-preset-expo` — the same one Metro uses.
A runner that does not go through Babel stops at the first import of the
framework. `jest.config.js` explains the two settings that make this work under
pnpm's non-flat `node_modules`.

## Configuration

Two variables, read at **build** time by `app.config.ts` and surfaced through
`Constants.expoConfig.extra`. Both are the names `apps/web` already uses, so a
deployment answers "where is the API" once rather than twice.

| Variable | Meaning |
|---|---|
| `IDEANEST_API_ORIGIN` | Where the Spring Boot service listens |
| `IDEANEST_SITE_URL` | The public origin whose links this application claims |

A value that is set but unusable **throws the build** rather than falling back.
An unset variable is somebody running locally; `IDEANEST_SITE_URL=ideanest.az`
with no scheme is a misconfiguration that would otherwise ship a build pointing
at localhost.

`eas.json` sets both per profile. `development` points at localhost, `preview`
at staging, `production` at production.

## Deep links (#114)

A campaign is at `/projects/<creator>/<campaign>` on the web and at the same path
here, so the link that opened the application and the route it lands on are one
string.

Three ways in — a push payload (`ideanest://…`), a shared https link, and a cold
start — all go through `src/lib/links.ts`, which **refuses** anything it does not
recognise. Expo Router can route an incoming URL by itself; what it cannot do is
refuse one, and on Android any application can send an implicit intent carrying a
URL.

The grant lives on the web side: `apps/web` serves
`/.well-known/apple-app-site-association` and `/.well-known/assetlinks.json` from
`src/lib/mobile/association.ts`. Both need identifiers that only exist once an
application has been signed:

| Variable | Set on |
|---|---|
| `IDEANEST_IOS_APP_ID` | `apps/web` — `<team prefix>.az.ideanest.app` |
| `IDEANEST_ANDROID_PACKAGE` | `apps/web` — `az.ideanest.app` |
| `IDEANEST_ANDROID_SHA256_FINGERPRINTS` | `apps/web` — comma-separated, one per signing certificate |

Unconfigured serves **404**, deliberately: both platforms already expect that
from a site with no application and retry, whereas iOS caches a file with the
wrong identifier in it for up to a week.

`scripts/check-association.mjs` asserts that the two halves name the same
application. Nothing else does, and the failure they produce is silent — the file
is fetched, disagreed with, and links quietly stop opening the application.

## Push notifications (#87)

`src/lib/push.ts` asks for the permission **at the moment it means something**,
never on launch: on iOS a declined permission cannot be asked for again from
inside the application.

Registration is `POST /v1/me/devices` and sign-out is `DELETE /v1/me/devices`.
A token belongs to whoever signed in most recently and to nobody else — two
people can share a phone, and a registration that stayed with the first would
deliver the second person's pledge confirmations to somebody else's lock screen.

Tapping a notification goes through the same parser a shared link does, so the
two cannot drift into "works from a link, does nothing from a notification".

## Offline (#115)

`src/lib/offline.ts` persists TanStack Query's own cache to MMKV, so the screens
never know: `useQuery` answers from the cache it already answers from, and the
only difference offline is that the background refetch fails.

**Saved campaigns, pledges and campaign pages survive a restart. Feeds and search
results do not** — a feed is a ranking computed at a moment, and restoring last
week's is worse than showing that the device is offline, because it looks
current.

`networkMode: 'offlineFirst'` is the setting the whole feature rests on. The
default pauses a query when the device reports no connection, so a cached
campaign would sit behind a spinner that never resolves.

## Builds and releases (#116)

`eas.json` has three profiles and `.github/workflows/mobile-release.yml` drives
them. The workflow **checks** on every change — typecheck, tests, the Expo config
resolving, and the association identifiers agreeing — and **builds** only on a
manual dispatch, because a store build is a deliberate act with a human behind
it.

Building needs Xcode and the Android SDK, which this repository does not own, so
the build runs on Expo's infrastructure and needs `EXPO_TOKEN` in the repository
secrets. Without it the workflow says so and stops rather than failing.

The Android submit goes to the `internal` track as a **draft**, which makes the
staged rollout a decision somebody takes in Play Console rather than a
consequence of a workflow finishing.

## What is not built

**Sign-in.** There is no form. `src/lib/use-session.ts` answers whether a refresh
token is in the keychain and the screens that need one say so; §4.2's mobile
authentication sits with the checkout work (#58), which is blocked on the payment
provider decision (#60). A sign-in form whose next screen cannot exist is a
promise the application does not keep.

**Checkout.** #58, blocked behind #60. The campaign page's call to action opens
the web checkout, which works today.

**Comments.** #113 asks for them and what is here is the count and a link. §4.6's
thread is moderated, reportable and rate-limited, and half of it is meaningless
without an account this application cannot yet create.

**Rich story formatting.** The story is a TipTap document and is rendered as
paragraphs of plain text — `src/lib/story.ts` argues why a second renderer that
disagreed with the web about list nesting would be worse than the missing bold.

**A device.** Nothing here has been run on one. The tests, the typecheck and the
Expo config resolution all pass; what they cannot cover is push delivery, the
universal-link grant, and store review, all three of which need signed builds and
credentials this repository does not hold.
