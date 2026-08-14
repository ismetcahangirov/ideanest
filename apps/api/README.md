# `apps/api` — IdeaNest API

Java 21, Spring Boot 3.5, Gradle. The backend for the web, mobile, and admin
clients. The full specification is in [`docs/architecture.md`](../../docs/architecture.md).

---

## Running it

```bash
cd apps/api

./gradlew bootRun        # start on http://localhost:8080
./gradlew build          # compile, run tests, produce the jar
./gradlew test           # tests only
```

There is no wrapper to install and no Gradle to install: `./gradlew` downloads
the distribution it is pinned to and verifies its checksum before running it.
A JDK 21 or newer must be on the path; the build compiles against a Java 21
toolchain regardless of which JDK launches it.

**Docker is required.** `bootRun` starts the PostgreSQL in `compose.yaml` and
stops it again on exit, and the tests start their own container. Running the
stack by hand instead:

```bash
docker compose up -d      # from apps/api
docker compose down       # add -v to discard the data
```

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
| `POST /v1/auth/refresh` | Rotates the refresh token and returns a new access token |
| `POST /v1/auth/logout` | Revokes the session. `204` even with no token |
| `GET /v1/me` | The signed-in account. The first endpoint behind a bearer token |

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
| Redis, object storage, and a mail catcher in the local stack | [#134](https://github.com/ismetcahangirov/ideanest/issues/134), [#139](https://github.com/ismetcahangirov/ideanest/issues/139) |
| PostGIS, needed for proximity search | [#47](https://github.com/ismetcahangirov/ideanest/issues/47) |
| Money type and arithmetic rules | [#133](https://github.com/ismetcahangirov/ideanest/issues/133) |
| Job queue and scheduler | [#134](https://github.com/ismetcahangirov/ideanest/issues/134) |
| Transactional outbox | [#135](https://github.com/ismetcahangirov/ideanest/issues/135) |
| OpenAPI contract and generated clients | [#136](https://github.com/ismetcahangirov/ideanest/issues/136) |
| Structured logging with redaction | [#137](https://github.com/ismetcahangirov/ideanest/issues/137) |
| Metrics, tracing, alerting | [#138](https://github.com/ismetcahangirov/ideanest/issues/138) |
