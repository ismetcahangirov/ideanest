# UI Kit — Dark Surface, Lime Accent

**Status:** authoritative. This document defines colour, surface, form, and
component behaviour for every IdeaNest surface.

Motion is specified separately in [`motion-system.md`](./motion-system.md).
The two are designed to be read together: this one answers *how it looks*, the
other answers *how it moves*.

---

## Contents

1. [System overview](#1-system-overview)
2. [Colour](#2-colour)
3. [Surface and elevation](#3-surface-and-elevation)
4. [Radius](#4-radius)
5. [Typography](#5-typography)
6. [Spacing and layout](#6-spacing-and-layout)
7. [Component catalogue](#7-component-catalogue)
8. [Applying the system to product screens](#8-applying-the-system-to-product-screens)
9. [Accessibility rules](#9-accessibility-rules)
10. [Implementation](#10-implementation)

---

## 1. System overview

### 1.1 Three structural laws

| # | Law | Consequence |
|---|---|---|
| **1** | **Black is the ground; white is an accent** | White panels and pills float *on* black. This inverts the usual dark theme, where white is the text colour and nothing more. |
| **2** | **Lime marks "now", nothing else** | Exactly one card in a row is lime: the active, urgent, or currently-relevant one. Lime is a state, not decoration. |
| **3** | **Everything is a pill or a rounded card** | Buttons, filters, tags, avatar clusters are fully rounded. Cards use 20–28px. There are no square corners. |

### 1.2 Why the density works

A dense dashboard should read as chaos. It does not, for three reasons:

- **Colour carries hierarchy.** Black is ground, dark grey is an ordinary card,
  white is focus, lime is urgent. A user reads priority from colour before
  reading a single word.
- **Form groups by function.** Pills are one category of thing (filters, tags,
  actions); cards are another. Shape encodes kind.
- **Spacing is tight but invariant.** Cards sit close together, and the gap
  between them never changes.

### 1.3 What this means for a funding platform

The visual language originates in a dense operational dashboard. IdeaNest has
two faces, and they suit it differently:

| Surface | Character | Fit |
|---|---|---|
| Dashboard, creator tools, admin | Dense, operational | **Direct fit.** Apply as specified. |
| Discovery, project pages | Image-led, exploratory | **Good fit.** A black ground makes project photography stronger. |
| Campaign story (long-form) | 2,000+ words of reading | **Needs an adjustment.** See below. |

> **A problem worth naming.** Long-form text set in white on pure black is
> tiring to read — light bleeds into the dark ground, an effect that is markedly
> worse for readers with astigmatism. This is not a flaw in the palette; it is a
> property of long-form text on high-contrast dark grounds.
>
> **The fix, without breaking the system:** set the story block on `--surface-2`
> rather than the page ground, use `--text-reading` instead of pure white, and
> open the line height to 1.75. Still the same dark system; simply not maximum
> contrast where maximum contrast hurts. Specified in §8.4.

---

## 2. Colour

### 2.1 Neutral surfaces

| Token | Value | Use |
|---|---|---|
| `--black` | `#000000` | Outer gutters only |
| `--surface-1` | `#0D0D0D` | Page ground |
| `--surface-2` | `#161616` | Standard card, panel |
| `--surface-3` | `#1F1F1F` | Nested block, input |
| `--surface-4` | `#2A2A2A` | Hover, selected |
| `--border` | `rgb(255 255 255 / 0.08)` | Card border — very faint |
| `--border-strong` | `rgb(255 255 255 / 0.16)` | Focus, active border |
| `--divider` | `rgb(255 255 255 / 0.06)` | Rules and separators |

> **Why the page ground is not pure black.** On OLED panels, pure black next to
> lit pixels smears during scroll, because the pixel transition from fully off
> is slower. `#0D0D0D` removes the artefact and is indistinguishable at rest.

### 2.2 Text

| Token | Value | Use | Contrast on `--surface-1` |
|---|---|---|---|
| `--text-primary` | `#FFFFFF` | Headings, names, figures | **20.4:1** AAA |
| `--text-secondary` | `rgb(255 255 255 / 0.64)` | Subtitles, descriptions | **9.2:1** AAA |
| `--text-tertiary` | `rgb(255 255 255 / 0.40)` | Meta, timestamps, placeholder | **4.9:1** AA at 16px+ |
| `--text-disabled` | `rgb(255 255 255 / 0.24)` | Disabled controls | 2.6:1 — non-text only |
| `--text-reading` | `rgb(255 255 255 / 0.92)` | Long-form body copy | 17.5:1 |
| `--text-on-lime` | `#0A0A0A` | Text on a lime surface | **15.8:1** AAA |
| `--text-on-white` | `#0A0A0A` | Text on a white surface | **19.3:1** AAA |

### 2.3 Lime — the brand accent

| Token | Value | Use |
|---|---|---|
| `--lime-300` | `#DCFB7A` | Highlight on a lime ground |
| `--lime-400` | `#D2F95C` | Hover |
| **`--lime-500`** | **`#C6F432`** | **Primary** — active card, urgent action, progress |
| `--lime-600` | `#B0DE1E` | Pressed |
| `--lime-700` | `#94BC15` | Border, icon |
| `--lime-glow` | `rgb(198 244 50 / 0.24)` | Outer glow, focus halo |

**The governing rule:**

> Lime is **always a surface**, never text on a light ground. Lime fill with
> near-black text. Lime text on white measures **1.3:1** and cannot be read.

Lime text is permitted only on a dark ground, where it measures 15.4:1 — for
example a small status label.

### 2.4 Status

| Token | Value | Use |
|---|---|---|
| `--success` | `#34D058` | Goal reached, payment collected, delivered |
| `--warning` | `#FFB020` | Final 48 hours, survey overdue |
| `--danger` | `#FF4438` | Payment failed, project suspended |
| `--info` | `#4A9EFF` | In review, informational |
| `--hot` | `#FF6B35` | Trending project |

**Lime and success are not interchangeable.** Lime says *hurry*; success says
*achieved*. A backer who sees lime and reads "everything is fine" has been
misled by the interface. The token file keeps them distinct and the test suite
asserts they differ.

**Dot indicator** — five dots expressing funding level:

```
○○○○○   0–25%     --text-disabled
●○○○○   25–50%    --warning
●●○○○   50–75%    --lime-500
●●●○○   75–100%   --lime-500
●●●●●   100%+     --success
```

On a lime card the status hues vanish — `--success` over `--lime-500` measures
roughly 1.2:1. Filled dots switch to `--text-on-lime` there.

### 2.5 White as an accent

| Token | Value | Use |
|---|---|---|
| `--white-surface` | `#FFFFFF` | Floating panel, primary pill, modal |
| `--white-muted` | `#F4F4F4` | Nested surface inside a white panel |

**When white:** the element demands attention, sits above the system (modal,
floating panel), or is the primary action.
**When lime:** the element is *current, active, or urgent*.

The two should not appear on the same card. One says "this matters"; the other
says "this is happening now". Together they say neither.

---

## 3. Surface and elevation

**There are no shadows in this system.** Depth is expressed through surface
colour.

```
Layer 0 — page ground        #0D0D0D
   │
   ├─ Layer 1 — card          #161616  + border rgb(255 255 255 / .08)
   │     │
   │     └─ Layer 2 — inner   #1F1F1F  (input, tag container)
   │
   ├─ Layer 3 — active card   #C6F432  (lime — state, not elevation)
   │
   └─ Layer 4 — floating      #FFFFFF  + shadow
```

The **only** shadows belong to white floating panels, because those are the only
things genuinely above the plane:

```css
--shadow-panel: 0 8px 32px -8px rgb(0 0 0 / 0.5);
--shadow-float: 0 24px 64px -12px rgb(0 0 0 / 0.7);
```

Do not put a shadow on a dark card. A dark shadow under a dark surface renders
as nothing while still costing a compositing pass.

---

## 4. Radius

| Token | Value | Use |
|---|---|---|
| `--radius-full` | `9999px` | Pills, filter chips, avatars, circular icon buttons |
| `--radius-xl` | `28px` | Large cards, panels, modals |
| `--radius-lg` | `20px` | Standard card, project card |
| `--radius-md` | `14px` | Nested blocks, inputs |
| `--radius-sm` | `10px` | Tags, small labels, thumbnails |

**Rule:** larger surface, larger radius. A nested element always takes a smaller
radius than its parent — approximately `parent_radius − padding`, so the curves
stay concentric.

---

## 5. Typography

### 5.1 Typeface

The system calls for a geometric grotesque: open counters, low contrast, a
single-storey feel at display sizes.

| Face | Character | Recommendation |
|---|---|---|
| **General Sans** | Geometric, contemporary, precise | **First choice** |
| **Sora** | More technical, strong numerals | Second |
| **Inter** | Neutral, widest language coverage | Safe default |

> **Latin Extended check.** The product ships in a locale requiring
> `ə ğ ı ö ş ü ç İ Ə Ğ`. Both General Sans and Sora cover these, but inspect the
> `ə` glyph specifically — in several faces it was added later and does not sit
> with the rest of the lowercase. Where there is doubt, choose **Inter**, in
> which it is part of the original design.

**Decision:** General Sans for display and headings, Inter for body and
numerals. Two families, no more.

### 5.2 Scale

```css
--text-display:  clamp(2.5rem, 2rem + 2.2vw, 4rem);        /* 40 → 64px */
--text-h1:       clamp(2rem, 1.6rem + 1.8vw, 3rem);        /* 32 → 48px */
--text-h2:       clamp(1.5rem, 1.3rem + 0.9vw, 2rem);      /* 24 → 32px */
--text-h3:       clamp(1.25rem, 1.15rem + 0.5vw, 1.5rem);  /* 20 → 24px */
--text-lg:       1.125rem;   /* 18px — card title */
--text-base:     1rem;       /* 16px — body */
--text-sm:       0.875rem;   /* 14px — subtitle, role */
--text-xs:       0.75rem;    /* 12px — tag, meta, count */
--text-2xs:      0.6875rem;  /* 11px — badge */
```

### 5.3 Weight and letter-spacing

| Role | Weight | Letter-spacing |
|---|---|---|
| Display figure | 600 | `-0.04em` |
| H1 | 600 | `-0.035em` |
| H2 / H3 | 600 | `-0.03em` |
| Card title (18px) | 500 | `-0.02em` |
| Body | 400 | `-0.01em` |
| Tag / badge | 500 | `0` |
| Button | 500 | `-0.01em` |

**Tracking tightens as size grows.** Letterforms appear to drift apart at
display sizes; negative tracking compensates. Omitting this is the single most
common reason large headings look cheap, and it is why this table exists rather
than a single global value.

### 5.4 Line height

| Context | Value |
|---|---|
| Display / H1 | `1.05` |
| H2 / H3 | `1.2` |
| Card title | `1.3` |
| Body (short) | `1.5` |
| **Long-form campaign story** | **`1.75`** |

---

## 6. Spacing and layout

### 6.1 Scale

A 4px base: `4 · 8 · 12 · 16 · 20 · 24 · 32 · 40 · 48 · 64 · 80 · 96`

### 6.2 Measured values

| Element | Value |
|---|---|
| Card padding | `20px` (small) / `24px` (large) |
| Gap between cards | `16px` |
| Gap between sections | `32px` |
| Pill padding | `10px 18px` |
| Circular icon button | `40px` (small `32px`) |
| Avatar | `40px` in a card, `28px` in a group, `56px` on a profile |
| Navigation rail width | `72px` |
| Top bar height | `64px` |

### 6.3 Dashboard skeleton

```
┌──┬────────────────────────────────────────────────────┐
│  │  [timeline pill]        [headline figures]         │
│  │────────────────────────────────────────────────────│
│r │  TITLE                        [+ New]              │
│a │                                                    │
│i │  Section  (count)   [search] [sort]  [chips...]    │
│l │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐      │
│  │  │  card  │ │  card  │ │ ACTIVE │ │  card  │  →   │
│  │  └────────┘ └────────┘ └─ lime ─┘ └────────┘      │
│  │                                                    │
│  │  Section  (count)   [search] [sort]  [chips...]    │
│  │  ┌────────┐ ┌────────┐ ┌────────┐                 │
│  │  │ ACTIVE │ │  card  │ │  card  │             →   │
│  │  └─ lime ─┘ └────────┘ └────────┘                 │
└──┴────────────────────────────────────────────────────┘
```

The organising idea is **horizontally scrolling card rails**, each with its own
count and filters. It presents a great deal of information without a long
vertical scroll.

---

## 7. Component catalogue

### 7.1 Card

Three variants at one size, encoding **state** rather than elevation:

| Variant | Fill | Text | Border | When |
|---|---|---|---|---|
| `default` | `--surface-2` | `--text-primary` | `--border` | Ordinary item |
| `active` | `--lime-500` | `--text-on-lime` | none | Current, priority, urgent |
| `floating` | `--white-surface` | `--text-on-white` | none + `--shadow-float` | Modal, floating panel |

```css
.card {
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 20px;
  transition:
    background-color 0.3s ease-in-out,
    transform 0.3s ease-in-out;
}
.card:hover { background: var(--surface-3); }
```

**Anatomy:**

```
┌─────────────────────────────────┐
│ [avatar]                    [↗] │  ← expand affordance, on hover/focus
│                                 │
│ Title                           │  ← 18px / 500
│ Subtitle                        │  ← 14px / secondary
│                                 │
│ Label                     ●●●●● │  ← label + dot indicator
│ [tag] [tag]                     │  ← 12px
└─────────────────────────────────┘
```

### 7.2 Pill button

```css
.pill {
  display: inline-flex; align-items: center; gap: 8px;
  height: 40px; padding: 0 18px;
  border-radius: var(--radius-full);
  font-size: 14px; font-weight: 500; letter-spacing: -0.01em;
  transition: background-color 0.15s ease-in-out, transform 0.15s ease-in-out;
}
.pill--primary { background: var(--white-surface); color: var(--text-on-white); }
.pill--accent  { background: var(--lime-500);     color: var(--text-on-lime); }
.pill--ghost   { background: var(--surface-3);    color: var(--text-primary); }
.pill--outline { background: transparent; border: 1px solid var(--border-strong); }

.pill:hover  { transform: translateY(-1px); }
.pill:active { transform: translateY(0) scale(0.98); }
```

**At most one `accent` pill per screen.** Beyond that, urgency stops meaning
anything.

### 7.3 Filter chip

```css
.chip {
  height: 34px; padding: 0 16px;
  border-radius: var(--radius-full);
  background: var(--surface-2);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 13px; font-weight: 500;
  white-space: nowrap;
}
.chip[data-active] {
  background: var(--white-surface);
  border-color: transparent;
  color: var(--text-on-white);
}
```

The selected state is **white, not lime** — a filter is never urgent.

Its container scrolls horizontally, with a masked right edge so "there is more"
reads without a scrollbar:

```css
.chip-row {
  display: flex; gap: 8px;
  overflow-x: auto;
  scrollbar-width: none;
  mask-image: linear-gradient(90deg, black 88%, transparent);
}
```

### 7.4 Circular icon button

```css
.icon-btn {
  width: 40px; height: 40px;
  display: grid; place-items: center;
  border-radius: var(--radius-full);
  background: var(--surface-3);
  transition: background-color 0.15s, transform 0.15s;
}
.icon-btn:hover { background: var(--surface-4); transform: scale(1.06); }
```

Variants: `light` (white), `accent` (lime), `danger`, `ghost`.
An accessible name is mandatory.

**Expand affordance (↗)** — top-right of a card, revealed on hover **and on
keyboard focus**:

```css
.expand-btn {
  position: absolute; top: 16px; right: 16px;
  width: 32px; height: 32px;
  background: var(--surface-4);
  opacity: 0;
  transition: opacity 0.2s, transform 0.2s;
}
.card:hover .expand-btn,
.expand-btn:focus-visible { opacity: 1; }
.expand-btn:hover { transform: translate(2px, -2px); }
```

Hover-only would make it unreachable without a pointer.

### 7.5 Tag

```css
.tag {
  height: 26px; padding: 0 10px;
  border-radius: var(--radius-sm);
  background: var(--surface-3);
  color: var(--text-secondary);
  font-size: 12px; font-weight: 500;
}
.tag--on-lime {
  background: rgb(10 10 10 / 0.10);
  color: rgb(10 10 10 / 0.72);
}
```

> Inside a lime card the default tag is invisible — dark on dark. Use the
> black-tint variant.

### 7.6 Avatar and avatar group

```css
.avatar { border-radius: var(--radius-full); object-fit: cover;
          border: 2px solid var(--surface-1); }

.avatar-group { display: flex; }
.avatar-group > * + * { margin-left: -10px; }
.avatar-group:hover > * + * { margin-left: -4px; }
```

Sizes: `28px` in a group, `40px` in a card, `56px` on a profile. With no image,
initials are derived from the name.

### 7.7 Headline figure

```css
.stat__value { font-size: var(--text-display); font-weight: 600;
               letter-spacing: -0.04em; font-variant-numeric: tabular-nums; }
.stat__label { font-size: 14px; color: var(--text-secondary); }
.stat__badge { height: 20px; padding: 0 7px; border-radius: var(--radius-full);
               font-size: 11px; font-weight: 600; }
.stat__badge--up   { background: var(--lime-500); color: var(--text-on-lime); }
.stat__badge--down { background: var(--danger);   color: var(--white-surface); }
```

Figures animate on entry, but over 800ms — not the two seconds a marketing site
would use. See [`motion-system.md`](./motion-system.md) §4.8.

`tabular-nums` matters: without it a counting figure jitters as digit widths
change.

### 7.8 Timeline

```css
.timeline {
  position: relative; height: 44px;
  border-radius: var(--radius-full);
  background: var(--lime-500);
  display: flex; align-items: center;
}
.timeline__now { position: absolute; width: 2px; height: 100%;
                 background: var(--text-on-lime); }
.timeline__item { position: absolute; transform: translateX(-50%); }
```

A lime fill is correct here: a live campaign is time-bound, and the strip exists
to say the clock is running.

**Product use:** launch → goal reached → deadline → payout.

### 7.9 Floating panel

```css
.floating-panel {
  background: var(--white-surface);
  color: var(--text-on-white);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-float);
  overflow: hidden;
}
```

Text inside is near-black, so `--text-secondary` does not apply. Use
`--text-on-white` at reduced opacity.

### 7.10 Navigation rail

```css
.rail { width: 72px; display: flex; flex-direction: column;
        align-items: center; gap: 12px; padding: 24px 0; }
.rail__item { width: 44px; height: 44px; border-radius: var(--radius-full);
              display: grid; place-items: center;
              color: var(--text-tertiary);
              transition: all 0.2s ease-in-out; }
.rail__item:hover        { background: var(--surface-3); color: var(--text-primary); }
.rail__item[data-active] { background: var(--surface-4); color: var(--lime-500); }
```

The active item gets a lime **icon**, not a lime fill. Permanent chrome should
not shout as loudly as a campaign about to close.

### 7.11 Progress bar

```css
.progress { height: 6px; border-radius: var(--radius-full);
            background: var(--surface-3); overflow: hidden; }
.progress__fill { height: 100%; border-radius: var(--radius-full);
                  background: var(--lime-500);
                  transition: width 0.8s cubic-bezier(0.4, 0, 0.2, 1); }
.progress--complete .progress__fill {
  background: var(--success);
  box-shadow: 0 0 12px var(--lime-glow);
}
```

The track fills to 100% and stops, but the value may exceed it. An overfunded
campaign shows its real figure as text while the track stays full.

### 7.12 Section header

```
Section title  (7 projects)      [search] [sort]   [All][Hot][...]
────────────────────────────────────────────────────────────────
```

The count is `--text-tertiary` at 12px — present, never competing with the
title.

### 7.13 Form primitives

Forms are where the platform takes money and where creators spend hours. Both
argue for the same thing: nothing here is decorative.

**The field wrapper owns the wiring.** `Field` generates the ids and hands the
control its `id`, its `aria-describedby` (hint **and** error, in that order),
its `aria-invalid`, and its `required`. A hint the assistive layer never reaches
is decoration, and remembering to wire it by hand is a thing people forget under
deadline. A field whose control is a *set* — radios, a drop zone — takes
`grouped`, and the label names the group through `aria-labelledby` instead of
pointing `htmlFor` at nothing.

**An error is text plus an icon, never a colour.** `--danger` with `CircleAlert`
and a sentence saying what to do. A red border alone says nothing to a screen
reader and nothing to a user with a colour-vision deficiency (§9.2). Lime never
marks an error: it means *urgent*, and an urgent-looking mistake reads as a call
to action.

**The input skin:**

```css
.input {
  height: 44px; padding: 0 14px;
  background: var(--surface-3);          /* §3: nested block, input */
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  transition:
    background-color 0.15s ease-in-out,
    border-color 0.15s ease-in-out;
}
.input::placeholder  { color: var(--text-tertiary); }   /* never --text-disabled */
.input:hover         { border-color: var(--border-strong); }
.input[aria-invalid] { border-color: var(--danger); }
.input:disabled      { opacity: 0.4; }
```

No component declares its own focus ring. The global `:focus-visible` rule
(§9.3) already draws it; a second one drifts and eventually contradicts the
first.

| State | Fill | Border | Notes |
|---|---|---|---|
| Rest | `--surface-3` | `--border` | |
| Hover | `--surface-3` | `--border-strong` | |
| Focus | `--surface-3` | `--border-strong` + global lime ring | Ring is never removed |
| Invalid | `--surface-3` | `--danger` | Always with the error text and icon |
| Disabled | `--surface-3` at 40% | — | Not a readable state; not tab-reachable |
| Checked / on | `--lime-500` | none | Mark in `--text-on-lime`, `data-on-lime` set |

**Checked is lime, and that is not a contradiction.** §8.1 already reads
`--lime-500` as "active choice" for a selected reward tier. A ticked box is the
same gesture. It is still not `--success`: nothing has been achieved by ticking
a box.

**The native `<select>` is a decision, not a shortcut.** A hand-built listbox
has to re-implement type-ahead, Home/End, PageUp/PageDown, the announcement
contract, and the platform wheel picker on iOS and Android — and it always gets
one of them wrong. `color-scheme: dark` makes the browser render the option list
to match (§9.4). The rich listbox — multi-select, async search — is overlay
work, not a form primitive.

**A switch is not a checkbox.** A checkbox selects something for later; a switch
takes effect now. `role="switch"` is what makes a screen reader say "on" rather
than "checked", and a live setting that announces itself as "checked" is a small
lie. The thumb moves with `transform`, never `left`.

**The drop zone's button is not optional.** Drag-and-drop is unreachable by
keyboard, by switch control, and on every touch device. Dragging is the
shortcut; the button is the control. Drag-over changes the instruction text as
well as the border, because colour alone carries nothing.

**Motion budget: 150ms colour and opacity, plus the switch thumb.** Nothing
else. Forms live on checkout ("near zero") and the campaign editor ("none") —
see [`motion-system.md`](./motion-system.md) §5. An animating field reads as
hesitation exactly where confidence is worth the most.

---

## 8. Applying the system to product screens

### 8.1 State-to-colour map

The most important table in this document:

| State | Surface | Example |
|---|---|---|
| Ordinary project card | `--surface-2` | Discovery grid |
| **Closing within 48 hours** | `--lime-500` | Urgent — demands attention |
| **Goal reached** | `--surface-2` + `--success` progress | Achieved, but not urgent |
| Editorially featured | `--surface-2` + lime border | `1px solid var(--lime-700)` |
| Trending | `--surface-2` + `--hot` icon | |
| **Payment failed** | `--surface-2` + `--danger` left rule | Banner and card |
| Suspended | `--surface-2` at 50% opacity | Inert |
| Selected reward tier | `--lime-500` | Active choice |
| Modal, checkout | `--white-surface` | Focus, above the system |

> **The distinction to hold on to:** lime means *urgent*, not *successful*. If
> the two blur, a user sees lime, concludes "all is well", and misses that the
> message was "hurry".

### 8.2 Discovery

```
┌──┬─────────────────────────────────────────────────┐
│  │  [search pill]            [currency] [profile]  │
│  │─────────────────────────────────────────────────│
│r │  Discover                                       │
│a │  [All][Technology][Games][Design][...]          │
│i │                                                 │
│l │  Closing soon      (24)      [search][sort]     │
│  │  ┌──────┐┌──────┐┌ LIME ┐┌──────┐          →   │
│  │  │image ││image ││image ││image │               │
│  │  │title ││title ││title ││title │               │
│  │  │▓▓▓░░ ││▓▓▓▓░ ││▓▓▓▓▓ ││▓▓░░░ │               │
│  │  └──────┘└──────┘└──────┘└──────┘               │
└──┴─────────────────────────────────────────────────┘
```

Project imagery runs full-bleed at the top of the card, with radius on the upper
corners only. The black ground is the system's largest advantage here: it makes
project photography stronger than a light theme can.

### 8.3 Creator dashboard

The closest fit for the system. Direct mapping:

| Pattern | Product use |
|---|---|
| Lime timeline strip | Campaign timeline: launch → goal → deadline |
| Headline figure row | Backers, funded percentage, days remaining |
| Card rail | Recent pledges, arriving live |
| Second card rail | Outstanding tasks: send survey, publish update |
| Filter chips | Filter by reward tier |
| Lime active card | An urgent task, such as failed payments to chase |
| Floating panel | Financial summary |

### 8.4 Campaign story (the long-form exception)

```css
.story {
  background: var(--surface-2);
  border-radius: var(--radius-xl);
  padding: 40px;
  max-width: 68ch;
}
.story p {
  color: var(--text-reading);
  font-size: 1.0625rem;
  line-height: 1.75;
  margin-bottom: 1.5em;
}
.story h2 { color: var(--text-primary); }
```

Four changes: a lighter ground, softer body text, generous leading, and a
measure limit. The system is unchanged; 2,000 words simply become readable.

### 8.5 Checkout

**The one screen where a white surface dominates.**

Someone about to part with money wants maximum clarity and a familiar context.
A white panel with near-black text serves both legibility and trust here.

```
┌─────────────────────────────────────┐
│  ● ● ○   Step 2 of 3                │  ← on the dark ground
│                                     │
│  ┌───────────────────────────────┐  │
│  │  WHITE PANEL                  │  │
│  │  Reward: Early Bird           │  │
│  │  ─────────────────────────    │  │
│  │  Reward            599.00     │  │
│  │  Shipping           25.00     │  │
│  │  ─────────────────────────    │  │
│  │  Total             624.00     │  │
│  │                               │  │
│  │  [ Confirm pledge ]  ← lime   │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

The confirm button is the only lime element on the screen, so attention lands
exactly where it should.

---

## 9. Accessibility rules

### 9.1 Measured contrast

| Pair | Ratio | Verdict |
|---|---|---|
| `#FFFFFF` on `#0D0D0D` | 20.4:1 | AAA |
| `rgb(255 255 255 / .64)` on `#0D0D0D` | 9.2:1 | AAA |
| `rgb(255 255 255 / .40)` on `#0D0D0D` | 4.9:1 | AA at 16px+ |
| `#0A0A0A` on `#C6F432` | 15.8:1 | AAA |
| `#C6F432` on `#0D0D0D` | 15.4:1 | AAA |
| **`#C6F432` on `#FFFFFF`** | **1.3:1** | **Prohibited** |
| `#FF4438` on `#0D0D0D` | 5.1:1 | AA |
| `#34D058` on `#0D0D0D` | 8.9:1 | AAA |
| `#34D058` on `#C6F432` | ~1.2:1 | **Prohibited** |

### 9.2 Prohibitions

| Do not | Instead |
|---|---|
| Lime text on a light surface | Lime fill, near-black text |
| White text on a lime surface | `--text-on-lime` |
| Convey meaning by colour alone | Colour + icon + label |
| Use `--text-disabled` for readable text | `--text-tertiary` at minimum |
| Shadow a dark card | A `--border` hairline |
| Set long-form white on pure black | `--surface-2` + `--text-reading` |
| Status hues on a lime surface | `--text-on-lime` |

### 9.3 Focus

```css
:focus-visible {
  outline: 2px solid var(--lime-500);
  outline-offset: 2px;
  border-radius: inherit;
}

/* A lime ring on a lime surface is invisible. */
[data-on-lime] :focus-visible,
[data-on-lime]:focus-visible {
  outline-color: var(--text-on-lime);
}
```

### 9.4 Additional requirements

- The dot indicator must never be the sole carrier of information — a numeric
  figure belongs beside it
- Status colours pair with an icon, for colour-vision deficiency
- Icon-only controls carry an accessible name
- Reduced motion is honoured — see [`motion-system.md`](./motion-system.md) §9.2
- `color-scheme: dark` is declared so browser chrome, scrollbars, and form
  controls follow

---

## 10. Implementation

### 10.1 Token file

The complete set lives in `packages/design-tokens/src/theme.css` and is
mirrored, value for value, in `src/index.ts` for React Native and canvas code.

**No component may contain a colour literal.** A test scans source and fails the
build on any hex that is not explicitly allowed. That guard is the reason the
token file can be trusted as the whole palette rather than most of it.

### 10.2 Tailwind

`packages/ui/src/styles.css` binds the custom properties to utility classes with
`@theme inline`, so `bg-surface-2` and `text-lime-500` resolve back to the token
file. The bridge introduces no values of its own.

```tsx
<article
  className="rounded-lg border border-white/8 bg-surface-2 p-5
             transition-colors duration-300 hover:bg-surface-3"
>
  <h3 className="text-lg font-medium tracking-tight text-white">{project.title}</h3>
  <p className="mt-1 text-sm text-white/64">{project.creator.name}</p>
  <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-surface-3">
    <div
      className="h-full rounded-full bg-lime-500 transition-[width] duration-800"
      style={{ width: `${Math.min(percent, 100)}%` }}
    />
  </div>
</article>
```

### 10.3 Mobile

The same tokens are consumed from `packages/design-tokens/src/index.ts`. In
addition: light status bar content, `#0D0D0D` splash background, and a dark
user-interface style declared in the app configuration.

### 10.4 Build order

| Step | Work | Depends on |
|---|---|---|
| 1 | Token package | — |
| 2 | Typeface selection and Latin Extended inspection | — |
| 3 | Primitives: card, pill, chip, icon button, tag, avatar | 1, 2 |
| 4 | Composites: headline figure, progress bar, section header | 3 |
| 5 | Layout: rail, top bar, card rail | 3 |
| 6 | Floating panel and modal system | 3 |
| 7 | Motion layer | 3–6 |
| 8 | Discovery and dashboard screens | 1–7 |
| 9 | Contrast audit and reduced-motion verification | 8 |

Storybook is not optional. Nineteen components with several variants each will
drift without a place to see them side by side.

---

## Appendix — one sentence

> Black stage, white light, lime signal. Little motion, soft form, hierarchy
> carried by colour.
