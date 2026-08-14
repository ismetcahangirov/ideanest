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

Most modules are still an empty package and a description. That is deliberate:
the package exists so that code lands where the architecture says it belongs,
rather than wherever the first commit happened to put it.

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
