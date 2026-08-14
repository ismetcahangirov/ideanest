# @ideanest/ui

Primitive, layout, and motion components for the IdeaNest design system.

Design decisions live in [`docs/ui-kit.md`](../../docs/ui-kit.md).
Motion decisions live in [`docs/motion-system.md`](../../docs/motion-system.md).

## Getting started

```bash
pnpm install
pnpm storybook          # http://localhost:6006
```

```bash
pnpm typecheck          # tsc --noEmit
pnpm test               # vitest: behaviour, accessibility, colour discipline
pnpm build-storybook    # static build; CI uses it to prove every story renders
```

## Components

### Primitives

| Component | Variants |
|---|---|
| `Card` + `CardTitle` / `CardSubtitle` / `CardFooter` | default · active · floating |
| `Pill` | primary · accent · ghost · outline · danger |
| `Chip` + `ChipRow` | selected / unselected, optional count |
| `IconButton` | default · light · accent · danger · ghost |
| `ExpandButton` | corner affordance revealed on hover and focus |
| `Tag` | default · onLime · onWhite · success · warning · danger · hot |
| `Avatar` + `AvatarGroup` | xs · sm · md · lg, initials fallback |
| `ProgressBar` | lime below 100%, success at or above |
| `StatBlock` + `StatRow` | md · lg, delta badge |
| `DotIndicator` | five-dot funding scale |
| `FloatingPanel` | white elevated surface |
| `RailHeader` + `CardRail` | horizontally scrolling section |

### Form

| Component | Purpose |
|---|---|
| `Field` | Label, hint, error, required marker — and the ids that wire them to the control |
| `TextInput` | sm · md · lg, leading and trailing adornments |
| `Textarea` | Same skin, optional `autoGrow` |
| `Select` | Native `<select>`, styled — accessible and mobile-correct by default |
| `Checkbox` | Real `<input type="checkbox">`, supports `indeterminate` |
| `Radio` + `RadioGroup` | `role="radiogroup"`, arrow keys from native radios |
| `Switch` | `role="switch"`, thumb moves with `transform` |
| `FileDropZone` | Drag-and-drop plus a keyboard-reachable picker button |

Two rules specific to forms: an error is **text plus an icon**, never a colour,
and checked/on is `--lime-500` with a `--text-on-lime` mark — "active choice"
per `docs/ui-kit.md` §8.1, still not `--success`.

### Overlays

| Component | Purpose |
|---|---|
| `Modal` | centre-stage dialog — the one white overlay |
| `Drawer` | edge-anchored dialog, right · left · bottom |
| `Popover` | anchored and non-modal, with placement flipping |
| `Tooltip` | describes its trigger, on hover **and** on focus |
| `ToastProvider` + `useToast` | live-region messages that never take focus |

All controlled (`open` + `onOpenChange`). Focus moves in on open and returns to
the trigger on close; `Escape` closes the topmost overlay only. See
[`docs/ui-kit.md`](../../docs/ui-kit.md) §7.14.

### Layout

| Component | Purpose |
|---|---|
| `Rail` + `RailItem` | left icon navigation |
| `TopBar` + `TopBarLink` | collapsing top navigation |
| `Timeline` | campaign time track with projected markers |

### Motion

| Component | Purpose |
|---|---|
| `FadeUp` | the single scroll-entry animation |
| `StaggerGroup` | orchestrates `FadeUp child` descendants |
| `FlipButton` | rotating-label call to action |
| `CountUp` | animated figure, 800ms |

## Three rules

**1. Lime means urgent, not successful.**
`--lime-500` says "act now". A campaign that reached its goal uses `--success`.
Conflating them tells a backer the opposite of the truth.

**2. Context decides the colour.**
Inside a lime card, `text-white/64` and `Tag variant="default"` are invisible —
use the `onLime` variants. The same applies to the white `FloatingPanel`, where
`text-on-white/64` is correct.

**3. No literal colours.**
Every colour comes from `@ideanest/design-tokens`. `pnpm test` fails the build
if a hex literal appears in source, so the token file is the whole palette and
can be trusted as such.

## How this is tested

- **Storybook** — appearance, variants, and context. `Patterns/Discovery Rail`
  composes every primitive and is the reference for visual regression.
- **Vitest** — behaviour and accessibility: ARIA wiring, keyboard reachability,
  boundary values, and the colour-discipline guard.

Storybook runs the accessibility addon with `test: 'error'`. Contrast problems
fail rather than warn, because the lime-on-white pairing measures 1.3:1 and is
easy to miss by eye.
