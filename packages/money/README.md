# `@ideanest/money`

Money on the client: parsing, comparing, and formatting — one implementation,
for every application that shows an amount.

```ts
import { formatMoney, parseAmount, toMoney } from '@ideanest/money';

formatMoney({ amount: '5000.00', currency: 'AZN' }); // '5,000.00 AZN'
```

## Why this is a package

It used to be `apps/web/src/lib/money.ts`. It moved when `apps/mobile` arrived
and needed to render the same figures.

CLAUDE.md §3 is the rule: **never use floating point for money**, and types
shared between packages live in a shared package rather than being duplicated.
Copying a hundred lines of rounding and grouping into a second application would
have satisfied neither. `0.1 + 0.2 !== 0.3` is somebody's pledge, and two copies
of the rule are two places for one of them to drift — silently, because both
would look right on every amount anybody tested by hand.

`apps/web/src/lib/money.ts` remains, as a one-line re-export, so that the fifty
modules already importing it did not have to change in the same commit as the
move. New code should import `@ideanest/money`.

## What is in it

| Concern | What it does |
|---|---|
| `Money` | The wire shape: `{ amount: string, currency: string }`. The amount is a **string**, always — a JSON number is an IEEE 754 double and cannot hold `599.00` exactly (docs/architecture.md §10.3) |
| `parseAmount` | A creator's typing, to a `Decimal`, or a named rejection. No `parseFloat` and no `Number()`: both silently accept `1e5`, `0x10` and `12abc`, and both lose precision on the way in |
| `toWireAmount`, `toMoney` | A `Decimal` back to the fixed scale the API expects |
| `formatMoney` | Grouped digits and the ISO code, never a symbol. There is no agreed symbol for the manat in either language the product ships in (§21.1), and `Intl`'s answer differs by locale |
| `SUPPORTED_CURRENCIES` | One entry, because phase 1 collects in AZN only (§21.2). Still a list, because the project currency is a real choice that phase 2 widens |
| `approximate`, `formatApproximate`, `rateFor` | §21.2's display currency (#327). One amount converted at a rate the service published, formatted with `≈` so nothing on a screen can be mistaken for what will be charged |

## What is deliberately not in it

**No `Decimal.set()`.** This module parses, compares and formats to a fixed
scale, none of which needs a global precision or rounding mode. Configuring the
constructor from a leaf module would change the behaviour of every other
consumer that imports `decimal.js`.

**No arithmetic between currencies.** `approximate` divides one amount by one
rate and stops. It never adds two currencies, never sums approximations, and
never produces a value the API will accept back — the server refuses
cross-currency arithmetic for the same reason (`CurrencyMismatchException`), and
§21.2 is explicit that the display currency is an approximation while collection
happens in the project's currency.

The direction is documented at every level and asserted against a figure computed
by hand, because it is the one mistake this feature can make: **one unit of the
quoted currency is worth `rate` of the base**, so an amount in the base is
*divided*. On the dollar at 1.70 a multiplication is merely wrong; on the lira at
0.0354 it is out by a factor of thirty.

## Tests

```bash
pnpm --filter @ideanest/money test
```

Money arithmetic is on CLAUDE.md §3's not-optional list, because it fails
silently and expensively.
