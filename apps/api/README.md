# `apps/api` — IdeaNest API

Java 21, Spring Boot 4.1, Gradle. The backend for the web, mobile, and admin
clients. The full specification is in [`docs/architecture.md`](../../docs/architecture.md).

---

## Running it

```bash
cd apps/api

./gradlew bootRun        # start on http://localhost:8080
./gradlew build          # compile, run tests, produce the jar
./gradlew test           # tests only
./gradlew exportOpenApi  # rewrite openapi.json from the running application
```

There is no wrapper to install and no Gradle to install: `./gradlew` downloads
the distribution it is pinned to and verifies its checksum before running it.
A JDK 21 or newer must be on the path; the build compiles against a Java 21
toolchain regardless of which JDK launches it.

**Docker is required.** `bootRun` starts the services in `compose.yaml` and stops
them again on exit, and the tests start their own PostgreSQL container. Running
the stack by hand instead:

```bash
docker compose up -d      # from apps/api
docker compose down       # add -v to discard the data
```

The stack is PostgreSQL and **Mailpit**, which accepts every message and
delivers none. Anything the platform emails locally is at
<http://localhost:8025> rather than in somebody's real inbox — including a
campaign the finaliser closed, which is the quickest way to see a real
notification end to end.

---

## Email

`NotificationChannel.EMAIL` has a transport since #86 (§12.3). The parts worth
knowing before changing anything:

- **It is SMTP.** `email_deliveries` records `accepted_at`, never
  `delivered_at`: a relay reports that it took the message and nothing about
  what happened next. Bounces, spam filing and opens need a provider webhook,
  and there is no provider.
- **One layout, twenty-two types.** `EmailComposer` builds an `EmailContent` in
  an exhaustive `switch` over `NotificationType`, so **adding a type fails to
  compile** until somebody decides what its email says. The words live in
  `src/main/resources/messages.properties`; the two layouts live in
  `src/main/resources/email/`.
- **Look at a template without sending it**:
  `GET /v1/admin/email-templates/{type}/preview` answers `text/html`, and
  `?format=text` answers the plain-text part — which is the one nothing else
  renders and therefore the one worth checking. `POST …/test-send` sends it to
  your own address and takes no recipient, deliberately.
- **The colours in `email/layout.html` are hex literals**, which CLAUDE.md §2
  forbids in source and which no mail client would resolve any other way.
  `EmailLayoutTests` asserts every one of them is a value
  `packages/design-tokens` publishes, so a token change that is not mirrored
  here fails the build.

---

## The published contract

`openapi.json` beside this file is the OpenAPI 3.1 document §10.1 says the public
API is described by. It is **generated from the controllers, committed, and
asserted current** — `OpenApiContractTests` fails when it stops describing the
service, and `packages/api-client` is generated from it in turn.

```bash
./gradlew exportOpenApi   # rewrite it, then read the diff before committing
```

A task rather than something `build` does, deliberately: a contract regenerated
by the build is a contract whose changes are invisible in review, which is the
same failure as accepting a visual snapshot without reading what changed. Every
line of that diff is something a client depends on.

The document is served at `GET /v3/api-docs` and is `permitAll`, because that is
what "published" means — a document behind a token is a document a build cannot
fetch. It describes endpoints rather than exposing them: every path in it is
still governed by `SecurityConfiguration`. Swagger UI is not on the classpath;
the `-api` starter carries no assets, and a browsable rendering belongs wherever
the documentation is hosted rather than inside the service that holds the payment
endpoints.

Three types are documented by hand because reflection describes the Java and not
the JSON — `Money`, `Patched<T>`, and `/v1/discover`'s filter vocabulary. See
`ContractSchemas` and `DiscoveryQueryDocumentation`, each of which says what it
would otherwise have got wrong.

---

## Database

PostgreSQL 16. Local credentials are in `compose.yaml` and `application-local.yml`
and are deliberately weak — nothing in either file is a secret, and neither is
read by a deployed environment. Deployed environments supply `DB_URL`,
`DB_USERNAME`, and `DB_PASSWORD`, and the service refuses to start without them
rather than falling back to something plausible.

### Migrations

Flyway, in `src/main/resources/db/migration`, applied at start-up in every
environment. Hibernate's `ddl-auto` is `validate` and will never be anything
else: Flyway owns the schema, and Hibernate's job is to refuse to start when the
mapping and the schema disagree.

| Rule | Enforced by |
|---|---|
| `V<version>__<snake_case_description>.sql` | `MigrationConventionTests` |
| One version number used once | `MigrationConventionTests` |
| Every migration carries a `-- Reverse:` block | `MigrationConventionTests` |
| A `DROP` or `TRUNCATE` carries a `-- Contract:` block | `MigrationConventionTests` |
| Applied checksums match the files | `DatabaseMigrationTests` |

**Why the reverse is a comment.** Flyway's community edition has no `undo`. The
reverse therefore has to be written down and reviewed alongside the forward
change; writing it during an incident, against a schema nobody can see, is how
a bad hour becomes a bad week. Where a change genuinely cannot be reversed —
data has been destroyed — the block says so and says why.

**Expand, then contract.** Under a rolling deployment two versions of the code
serve traffic at once. A migration that drops a column the previous version
still selects breaks live requests. So the release that stops using something
and the release that removes it are different releases, and the removal says
which release preceded it.

### Tests

Integration tests run against a real PostgreSQL through Testcontainers. There is
no in-memory substitute here on purpose: H2 does not reproduce PostgreSQL
locking, constraints, or `numeric` semantics, which is precisely the behaviour
a funding platform depends on.

Extend `AbstractIntegrationTest` for anything needing the database. Sharing one
annotation set lets Spring cache a single context, so one container serves the
whole suite instead of one per class. A test of a pure function should not
extend it.

**Google and Apple are never called from a test.** `OidcProviderStub` runs
WireMock in the test JVM, serves a JWKS, and signs ID tokens with a key pair it
generates — which is the only way to produce the cases that matter: a wrong
audience, an expired token, a token signed by a key the provider does not
publish. It is wired in from `AbstractIntegrationTest` rather than from the test
class that uses it, because a class with a property source of its own gets a
context of its own and a second PostgreSQL container with it.

Integration tests run under the `test` profile, which mirrors deployed
configuration rather than the developer's — a health endpoint that hides detail
in production and shows it locally has to be asserted against the production
shape or the assertion proves nothing.

---

## Health

| Path | Purpose |
|---|---|
| `/actuator/health` | Aggregate status |
| `/actuator/health/liveness` | The process is alive; restart it if this fails |
| `/actuator/health/readiness` | The process can serve traffic; remove it from the load balancer if this fails |

The two probes are separate on purpose. An instance that is alive but not ready
— still warming a connection pool, say — should be taken out of rotation, not
killed and restarted, which would make a slow start-up into a restart loop.

Nothing else is exposed. `/actuator/env` and `/actuator/configprops` print
configuration, and configuration contains secrets. An endpoint is opened when
something needs it and there is an access control story for it, not by default.

Health responses carry a status and no component breakdown. Naming our
dependencies to an unauthenticated caller tells an attacker what to attack.

---

## Layout

Packages are organised **by feature, not by layer**. Everything about pledging
lives under `pledge`; there is no `repository` package holding every repository
in the service.

```
az.ideanest
├── auth              registration, sign-in, tokens, sessions, two-factor
├── user              accounts, profiles, deletion and export
├── project           campaigns and their state machine
├── reward            reward tiers, items, stock
├── pledge            pledge creation, editing, cancellation, checkout
├── payment           charges, refunds, chargebacks, provider adapters
├── ledger            the double-entry record
├── payout            what a creator is owed, approval, sending
├── discovery         browsing, filtering, search, ranking, curation
├── pledgemanager     surveys, addresses, upgrades, shipping, tax
├── community         updates, comments, backer signals
├── notification      email, push, in-app, preferences
├── media             uploads, validation, transcoding state
├── moderation        reports, review queues, suspension
├── analytics         campaign metric aggregation
├── admin             internal operations tooling
└── shared            money, outbox, idempotency, audit
```

Within a module:

| Layer | Holds |
|---|---|
| `domain` | Entities, value objects, and rules that hold regardless of the caller |
| `application` | Services and use cases. The transaction boundary |
| `infrastructure` | Repositories, clients, adapters |
| `api` | Controllers and the request and response types they bind |

**A module reaches another module through its `application` layer only.**
Reaching into another module's `domain` or `infrastructure` couples the two to
each other's internals, which is exactly what the boundary exists to prevent —
and what would make extracting a module into its own service expensive later.
`shared` is the exception: it is cross-cutting by definition, which is also why
nothing belonging to one feature may be put there.

`ModuleBoundaryTests` enforces all of it — the boundary, the rule that `domain`
knows nothing of `infrastructure` or `api`, and the absence of cycles between
modules. A rule that lives only in a comment survives until the first afternoon
somebody is in a hurry.

**Where a question crosses that boundary, it crosses as a port in `shared`.**
Three so far, each one interface, each implemented by the module that owns the
rows, and each named in `ModuleBoundaryTests` so the route is checked rather than
merely available:

| Port | The question | Answered by |
|---|---|---|
| `shared.access.ProjectAuthorisation` | May this account do this on this campaign? (#236) | `project` |
| `shared.audience.ProjectAudiences` | Who are these people on this campaign? (#245) | `pledge` |
| `shared.project.ProjectSummaries` | What is this campaign called, and where does it live? (#249) | `project` |

The alternative each replaced was a method per question on the owning module's
service — a published surface that grows without bound — or reaching into another
module's tables, which is the coupling the boundary exists to prevent.

An entity in one module therefore refers to another module's aggregate by its
identifier, not by a JPA association: `Session` holds a `userId`, not a `User`.
The foreign key still exists in the database, because referential integrity is
the database's job and not a convention.

Most modules are still an empty package and a description. That is deliberate:
the package exists so that code lands where the architecture says it belongs,
rather than wherever the first commit happened to put it.

---

## Authentication

| Endpoint | Effect |
|---|---|
| `POST /v1/auth/register` | Creates an unverified account and sends a verification link. Always `202` |
| `POST /v1/auth/verify-email` | Redeems the link. `204` once, `400` thereafter |
| `POST /v1/auth/login` | Starts a session. Returns an access token; the refresh token goes in a cookie, or in the body if the client asks |
| `POST /v1/auth/oauth/{provider}` | Signs in with a Google or Apple ID token. Same session, same tokens, same cookie as `/login` |
| `POST /v1/auth/refresh` | Rotates the refresh token and returns a new access token |
| `POST /v1/auth/logout` | Revokes the session. `204` even with no token |
| `POST /v1/auth/2fa/enable` | Starts a TOTP enrolment. Costs the password. Does **not** switch two-factor on |
| `POST /v1/auth/2fa/confirm` | A current code switches it on and returns the recovery codes, once |
| `POST /v1/auth/2fa/verify` | The second half of a sign-in: a challenge and a code, for tokens |
| `POST /v1/auth/2fa/disable` | Switches it off. Costs the password **and** a code |
| `GET /v1/auth/sessions` | The user's live devices, with the current one marked |
| `DELETE /v1/auth/sessions/{id}` | Revokes one device. `404` if it is not theirs |
| `GET /v1/me` | The signed-in account. The first endpoint behind a bearer token |
| `GET /v1/me/export` | A JSON copy of everything held about the account. Rate limited |
| `POST /v1/me/deletion` | Closes the account after a 30-day delay. Requires the password |
| `DELETE /v1/me/deletion` | Withdraws a pending deletion. `204` either way |

Everything else is denied by default. Forgetting to state who may call a new
endpoint produces a `401` in a test rather than an open door in production.

**Registration does not say whether an address is already registered.** An
endpoint that answers "that email is taken" is an account enumeration oracle:
feed it a breach list and it returns the subset of those people who are backers
here. Both paths return the same status and the same body; what differs is the
message the address receives, and that goes to its owner rather than to whoever
typed it. The cost is that the sign-up form cannot say "you already have an
account" — the email says it instead.

**Passwords** must be 12–256 characters and may not contain the address they
protect. There are no composition rules: they reliably produce `Password1!` and
a note on a monitor, and NIST dropped them for that reason. Hashing is Argon2id
at OWASP's parameters, configurable under `ideanest.auth.argon2` — raising them
later rehashes on next sign-in rather than locking anyone out, because Argon2's
encoded output carries the parameters it was made with.

**Tokens** — verification links today, refresh tokens next — are 256 bits from
`SecureRandom`, stored only as SHA-256, and spent by a conditional update so
that two simultaneous redemptions cannot both succeed. The hash is unsalted with
no work factor, which would be indefensible for a password and is correct here:
there is no dictionary against 256 random bits, and the hash is computed on
every refresh.

**Verification email** is not really sent. `LoggingVerificationNotifier` writes
a line instead, and writes the link itself only under the `local` profile.
Transactional email is #86.

**Rate limiting is in-process**, which is correct for one instance and wrong for
two — each replica enforces the limit separately. The shared counter is #142.

### Signing in with Google and Apple

The client gets an ID token from the provider and posts it. Nothing else in the
request says who the person is — no subject, no address, no "email verified".
Those are read out of the token, and only after it has been verified:

- **signature** against the provider's JWKS, from configuration, never from the
  token's own `jku` header
- **RS256**, pinned. A decoder that reads the algorithm out of the token accepts
  `alg: none`, and accepts an HMAC token signed with the RSA public key
- **`iss`**, **`aud`**, **`exp`**. `aud` is the one that matters most: Google
  signs valid tokens for every developer who asks, and the client identifier is
  what makes one ours
- **age**, from `iat`, capped at five minutes. Google's tokens live an hour; one
  produced by the sign-in happening now is seconds old
- **`nonce`**, which must match what the client bound its authorisation request
  to. Apple's native flow hashes it before it reaches the token, so what the
  client sends us is whatever it sent the provider — we compare, we do not
  interpret

**The account is the `(provider, subject)` pair, never the address.** Both
providers let a person change their email, and matching on it means whoever
holds that address next inherits the account.

**Linking to an existing account requires both sides to have proven the
address.** A verified provider address matching a *verified* account links
automatically. A verified provider address matching an *unverified* account is
refused with a 409 — anyone can register an address they do not own and choose
the password, so linking would hand them an account that has since become
somebody else's. An *unverified* provider address creates nothing and links to
nothing, and is refused with the ordinary message.

A user created this way has no password: `user_credentials` simply has no row.

**Configuration** is `ideanest.auth.oauth.providers.*`. Issuers and key set
addresses are checked in — they are facts about Google and Apple. Client
identifiers come from the environment, and a provider without them is not
enabled here: its endpoint answers `501`, rather than the service refusing to
start over a feature nobody has called. A provider configured *in part* —
identifiers with no issuer — does stop start-up, because that is a mistake and
its other failure mode is a 401 nobody can explain.

**What is not done yet.** The nonce is the client's, so it binds a token to a
request without proving freshness; server-issued nonces need storage shared
across replicas (#134). There is no endpoint to link or unlink a provider from
account settings (#25 follow-up), so the only linking is the automatic one
above. Apple's client secret — a signed ES256 JWT rather than a static string —
is not needed for ID token verification and is not implemented; it becomes
necessary for token revocation on account deletion (#28).

### Tokens

| | Access token | Refresh token |
|---|---|---|
| Form | JWT, RS256 | Opaque, 256 bits |
| Lifetime | 15 minutes | 30 days, absolute |
| Stored | Nowhere | As SHA-256, in `refresh_tokens` |
| Sent as | `Authorization: Bearer` | httpOnly cookie, or the body for native clients |

RS256 rather than a shared secret: with HMAC, everything that can verify a token
can also mint one, so the first service that needs to check a token has to be
given the ability to impersonate anybody.

The access token carries `amr`: `["pwd"]`, or `["pwd", "otp", "mfa"]` when the
session proved a second factor. A refresh carries it across unchanged — a
refresh proves possession of a token, not a factor, so it must neither add the
claim nor lose it.

**A session is the refresh token family, and rotation is enforced.** Every
refresh spends the presented token and issues a new one. A spent token should
never appear again, so when one does, two parties hold the same credential and
there is no way to tell which is the user — the whole session is revoked with
`TOKEN_REUSE`. Revoking only the presented token would leave whoever holds the
newest one signed in, and that might be the thief.

The claim is atomic: `UPDATE ... WHERE used_at IS NULL`. Two refreshes arriving
together cannot both succeed, and the loser is treated as reuse.

> **Clients must refresh single-flight.** Two concurrent refreshes with the same
> token look exactly like theft and will sign the user out. Concurrent requests
> wait on one in-flight refresh; they do not each start their own.

**Revoking a session does not reach an access token already issued.** Nothing is
looked up when one is verified — that is what stateless means, and it is why the
lifetime is fifteen minutes. `TokenApiTests` asserts this rather than leaving it
to be discovered.

**Signing keys** come from `ideanest.auth.token.private-key-pem` and
`public-key-pem`. Outside `local` and `test` the service refuses to start
without them; generating one would sign tokens with a key that differs per
replica and changes on every restart, signing people out at random in a way
nobody could diagnose.

---

## Two-factor authentication

Time-based one-time passwords, RFC 6238: HMAC-SHA1, six digits, thirty-second
steps. Written out in `auth.domain.Totp` rather than taken from
`dev.samstevens.totp` — that library's last commit is from November 2020 with no
release since 1.7.1, and an unmaintained dependency on the authentication path
buys us nothing that forty lines of `javax.crypto` does not. `TotpTests` checks
it against the RFC's own test vectors, which is the assertion that matters: if
our arithmetic disagrees with an authenticator app, every enrolment is a
lockout.

**One step of skew either side.** Phone clocks drift and people start typing at
the end of a window. The cost is that three codes are valid at any moment, so a
guess is three in a million — which is only a small number because of the rate
limit, and that is why the limit is not optional.

**Enrolling is not enabling.** `2fa/enable` generates a secret and returns it
once, as base32 and as an `otpauth://` URI the client renders as a QR code.
Two-factor is off until `2fa/confirm` accepts a current code. A user whose phone
dies between the two calls signs in exactly as before.

**A password alone stops producing a session.** With two-factor on, `login`
returns `{"twoFactorRequired": true, "challenge": …}` and nothing usable. The
challenge is 256 bits, stored as SHA-256, single use, five minutes, and retired
when a new one is issued — so collecting challenges does not buy more guesses.
It carries the device description from the first call, so the session the second
call creates describes the device that actually signed in.

**A code cannot be replayed.** The accepted time step is recorded and only a
strictly greater one is taken afterwards, so a code works once rather than for
the ninety seconds the skew window spans. The code that confirms an enrolment is
spent the same way.

**Recovery codes** are ten values of a hundred bits, shown once at confirmation
and stored as SHA-256 — unsalted and with no work factor, for the same reason a
refresh token is. Argon2 would be wrong twice over here: there is no dictionary
against a hundred random bits, and the codes are checked on an endpoint an
attacker can reach with a stolen challenge, which would let them spend 19 MiB of
our memory per guess.

**Switching it off costs the password and a code**, or the password and a
recovery code for somebody whose phone is the reason they are asking. Either
half alone would make the whole control worth one password.

**Rate limits.** Five code attempts per challenge; ten enrolment changes per user
per fifteen minutes; `2fa/verify` also counts against the per-address sign-in
allowance, because it is half of a sign-in.

### Requiring it for a payout

Two things are exposed, and neither is enforcement:

| Where | What |
|---|---|
| `sessions.two_factor_at` | When this session proved a second factor, or null |
| `amr` in the access token | `["pwd"]`, or `["pwd", "otp", "mfa"]` |
| `auth.application.TwoFactorPolicy` | `isEnabledFor(userId)`, `isProvedBy(sessionId)` — the front door for another module |

The question a payout must ask is `isProvedBy`, not `isEnabledFor`: an account
can switch two-factor on and still hold sessions that predate it, and a token
minted from one of those proves only a password. **Nothing calls this yet** —
payouts arrive with [#69](https://github.com/ismetcahangirov/ideanest/issues/69),
and that is where the check belongs.

**The TOTP secret is not encrypted at rest.** It cannot be hashed — verifying a
code means recomputing the HMAC — so the control that belongs here is encryption
with a managed key, and there is no key management in the platform yet. Until
there is, the secret is protected exactly as well as the database is, which is
worth knowing before treating two-factor as a defence against a database
compromise.
## Account deletion and export

The full policy, field by field, is in [`docs/architecture.md`](../../docs/architecture.md)
§17.4. What matters when reading the code:

**Deletion needs the password, not just the token.** An access token is fifteen
minutes of trust that a script or a proxy log can leak; a deletion that needed
only that is a vandalism tool. `POST /v1/me/deletion` verifies the password,
which also makes it a password oracle — hence the per-account rate limit.

**The grace period is thirty days, and the account stays usable in exactly three
ways:** sign in, read itself, export itself, cancel. `deleted_at` is *not* set
when the deletion is requested, because every finder excludes soft-deleted rows
and the account has to remain findable to be recovered. The restriction is
enforced in `SecurityConfiguration`: the access token carries a
`deletion_pending` claim, a closing account is not granted `ACCOUNT_ACTIVE`, and
`anyRequest()` requires it. Stated as an authority the account *has* rather than
a flag it lacks, so that a missing claim denies rather than allows.

Sessions are revoked when deletion is requested and stay revoked after a
cancellation. The user has just proved they can sign in; the other devices have
not.

**After the grace period the account is anonymised, not deleted.** The row
survives with its identifier and its foreign keys intact, because "pledge #123
was made by user X" has to stay true after X leaves. The email, name, slug,
avatar, and biography are overwritten; the password credential and every
verification token are deleted; the sessions keep their timings and lose their
IP addresses and user agents.

**The address is released by anonymisation** and not before. Holding it in
reserve permanently would mean retaining the address — or a hash of it, which
for an enumerable space is the same thing — in order to prove we no longer have
it.

**The job is `@Scheduled`, hourly, and belongs on the durable scheduler**
(#134). It is safe on more than one instance: the row is locked before the
decision, so one caller does the work and the rest find it done. Each account is
its own transaction, so a failure stalls one account rather than the batch. Under
the `test` profile the cron is `-`, and the tests call
`AccountAnonymisationJob.anonymiseDueAccounts(Instant)` with the moment they
want to test instead of waiting thirty days.

**The export contains no credentials.** Not the password hash, not the hash of
any refresh or verification token. An export is a copy of what we know about
somebody, not a copy of their keys, and a password hash is the one field here
worth cracking because people reuse passwords. The rest of the security history
*is* included, because it is the part that answers "was somebody else in my
account".

`AccountSecurity` is an interface in `user` implemented in `auth`. Closing an
account needs a password check, session revocation, and credential destruction,
all of which live in `auth` — but `auth` already depends on `user`, so a call the
other way would be a cycle. The dependency is inverted instead.

---

## Build conventions

| Convention | Reason |
|---|---|
| Java toolchain 21, declared in the build | The same bytecode from any developer machine and from CI |
| `-Xlint:all -Werror` | A warning nobody is forced to read is a warning nobody reads |
| Gradle wrapper pinned with a SHA-256 | Without the checksum the build runs whatever that URL serves |
| `gradlew` committed with LF and the executable bit | CI runs it on Linux; a CRLF shebang fails with an error that does not say why |

---

## What is not here yet

| Missing | Tracked by |
|---|---|
| Encryption at rest for the TOTP secret, and the key management it needs | no issue yet |
| A payout action that actually requires two-factor | [#69](https://github.com/ismetcahangirov/ideanest/issues/69) |
| Two-factor over SMS, as a fallback (A-08) | no issue yet |
| Redis and object storage in the local stack. A mail catcher is there since #86 -- Mailpit, on 1025, with its inbox at http://localhost:8025 | [#134](https://github.com/ismetcahangirov/ideanest/issues/134), [#139](https://github.com/ismetcahangirov/ideanest/issues/139) |
| PostGIS, needed for proximity search | [#47](https://github.com/ismetcahangirov/ideanest/issues/47) |
| Money type and arithmetic rules | [#133](https://github.com/ismetcahangirov/ideanest/issues/133) |
| A postal address in §4.7's CD-11 backer export (#79). The file carries the name, the email, the tier, the amounts, the state, the destination **country** and the instant — every fact the platform holds about a backer of a running campaign. `pledges` has no street, because §4.8's PM-07 collects one after the campaign closes; a column of blanks would look like the backers declined to give one | [#75](https://github.com/ismetcahangirov/ideanest/issues/75) |
| §4.7's CD-16 financial summary. Gross, fees, tax and net cannot be stated: there is no `ledger_entries`, no `transactions`, and no processing fee to subtract until a provider is chosen. A "net payout" computed from the 5% platform fee alone would be a number a creator would plan around and would be wrong by the processing fee | [#99](https://github.com/ismetcahangirov/ideanest/issues/99), [#62](https://github.com/ismetcahangirov/ideanest/issues/62), [#60](https://github.com/ismetcahangirov/ideanest/issues/60) |
| Job queue and scheduler | [#134](https://github.com/ismetcahangirov/ideanest/issues/134) |
| Push notifications. Email is real since #86; push is still `UndeliverableChannelSender`, which writes a log line and returns | [#87](https://github.com/ismetcahangirov/ideanest/issues/87) |
| Verification links, collaborator invitations, and launch reminders as **email**. Each still reaches its own port and a logging adapter rather than the notification queue, so #86's transport does not carry them -- they are not `NotificationType` rows | no issue yet |
| Bounce handling, a suppression list, and open tracking. All three need a provider webhook, and §16 chose an SMTP relay | no issue yet |
| Existing announcements moved onto the outbox. The table, the relay, and the guarantee are built (#135), and nothing routes through them yet: `AuthEvents`, `ProjectEvents`, and `LaunchReminderDelivery` still publish from after-commit listeners, so a crash between the commit and the send still loses the message | no issue yet |
| Structured logging with redaction | [#137](https://github.com/ismetcahangirov/ideanest/issues/137) |
| Metrics, tracing, alerting | [#138](https://github.com/ismetcahangirov/ideanest/issues/138) |
