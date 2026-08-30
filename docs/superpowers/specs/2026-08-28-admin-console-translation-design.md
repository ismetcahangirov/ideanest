# Translating the administration console's screens

Issue #324, epic #259. Part of the message catalogue work that #365 began.

## The gap

The console's **frame** is key-based: the bar, the rail, and the index that lists
§4.11's sixteen modules. Everything inside it is an English literal — twenty-four
components under `components/admin`, twenty-eight route files under
`app/[locale]/admin`, and the label tables in `lib/admin`.

`apps/web/README.md` and `docs/architecture.md` §21.1 both record this, so it is a
known gap rather than a discovered one. It measures roughly 560 distinct strings,
which is 2,240 messages across the four languages.

The reason it was left is written down in `lib/i18n/admin-copy.ts` and is no longer
believed: that the console's readers are staff and staff can read English. A
moderator who reads Azerbaijani is not a different class of reader from a backer
who does.

## Shape

### Catalogue

The existing `admin` namespace grows four children:

| Node | Holds | Keyed by |
|---|---|---|
| `admin.common` | Words every screen repeats: table chrome, `Save`/`Cancel`, empty and loading states, pagination | its own names |
| `admin.refusals` | `consoleMessageFor`'s outcomes, as templates carrying `{subject}` and `{capability}` | the §10.4 refusal code |
| `admin.screens.<CODE>` | One screen's own words | the §4.11 module code |
| `admin.pages.<route>` | A route's `metaTitle`, `metaDescription`, `title`, `intro` | the href, as `admin.links` already is |

Keying by the §4.11 code and by the href rather than by a third name of its own is
the decision `admin-copy.ts` already made for the frame, and for the reason it
gives: the specification, the issues and the router agree on those identifiers
already, so there is nothing extra to keep in step.

### Copy modules

Five modules under `lib/i18n/admin/`, grouped as `CONSOLE_GROUPS` groups the rail —
`content`, `curation`, `people`, `money`, `platform` — plus `common-copy.ts` for
the chrome and the refusals.

Rejected: one module per screen (twenty-six files, and twenty-six more exports on
`shell-copy.server.ts`, which is ceremony rather than isolation), and one large
`admin-copy.ts` (about 1,500 lines of interfaces in a file nobody can hold in
their head).

### Delivery

Twenty-four of the screens are `'use client'`. Copy is resolved on the **server**
and passed as a `copy` prop, which is what `InboxPanel` and every other translated
client component in this application does. A `NextIntlClientProvider` in a shared
layout is measured at up to 27.4 KiB on every route in the group, and the console
has twenty-eight routes.

Route files gain `generateMetadata` reading `admin.pages.<route>`, replacing the
literal `privatePageMetadata({ title, description })` each carries now.

### The label tables in `lib/admin`

`refusals.ts`, `staff.ts`, `audit.ts`, `payments.ts`, `disputes.ts`, `refunds.ts`,
`tickets.ts`, `payouts.ts`, `ledger.ts`, `reconciliation.ts`, `fees.ts`,
`taxonomy.ts`, `curation.ts`, `health.ts`, `flags.ts` and `email-templates.ts`
return finished English sentences today. They become pure: they return the stable
code, and the caller looks the word up in the catalogue. `lib/notifications/describe.ts`
was refactored the same way in #365 and is the precedent to follow.

### `ConsoleRefusal` stops being blocked

Its sentence names the thing a screen was about to show — "…to read the audit
trail" — and the noun comes from the screen, which is why the README records it as
untranslatable on its own. Once every screen has a catalogue node, each supplies
its own `subject`, the sentence becomes a template, and the exception closes with
the rest.

## Tests

Component suites build their `copy` prop with `translatorFor()` over
`messages/en.json`, never by retyping the sentences — `src/test-copy.ts` explains
why at length. `catalogue.test.ts` then covers all four languages at once: equal
key sets, no empty message, no Cyrillic homoglyph in a Latin-script language, no
Greek anywhere, balanced rich-text tags, one dash convention.

Suites to update: `components/admin/console.test.tsx`,
`components/admin/UserDirectory.test.tsx`,
`components/admin/ReconciliationPanel.test.tsx`, `lib/admin/console.test.ts`,
`lib/admin/staff.test.ts`.

## Documentation

`apps/web/README.md`'s "what is still English" table loses the console row and the
`ConsoleRefusal` paragraph. `docs/architecture.md` §21.1's note loses the console
from its list.

## Out of scope, deliberately

The campaign editor, the creator dashboard, the eleven account panels, and moving
`DEFAULT_LOCALE` to `az`. Each is a separate part of #324 and any of them would
double this change.

## Risks

`performance/budgets.json` carries a ceiling for every console route and fails a
route that drops more than 15% under it. Moving literals out of client components
lowers their bundles slightly; the budgets are re-measured after the build and
updated if the drop is real.
