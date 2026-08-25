import type { ReactNode } from 'react';
import { SiteShell } from '../../../../components/shell/SiteShell';

/**
 * The frame around the campaign editor — §4.13 WS-01 and WS-02, issue #347.
 *
 * <h2>Why this file exists</h2>
 *
 * Until it did, all six editor tabs rendered with no header and no footer. `EditorShell`
 * draws the campaign's title, its state, the save indicator and a row of section links —
 * everything about the campaign being edited, and nothing that leaves it. A creator who
 * finished a draft had no way to their dashboard, their other campaigns, or the site at all
 * except the browser's back button.
 *
 * Third instance of the root cause #343 named, and the same four-line fix.
 *
 * <h2>The motion budget permits this, and it is worth writing down why</h2>
 *
 * `docs/motion-system.md` §5 gives this surface **"None — autosave indicator only"**, and
 * the site header collapses on scroll. Those do not conflict. The same table gives the shell
 * a row of its own — "**One** — §4.7's collapse, and nothing else" — with the reason "it is
 * on every route in the table below it, so its budget is paid on all of them at once". The
 * shell's one animation is accounted for ABOVE the surface rather than inside it, which is
 * exactly the arrangement that sentence describes. Nothing here spends the editor's own
 * budget, which stays at zero.
 *
 * `/settings/*` and `/account/*` made the same trade in #275 against the same "None" row.
 *
 * <h2>Not `AdminArea`'s argument, either</h2>
 *
 * The admin console is the one working surface that refuses this header (#294), because it
 * offers Discover, the categories, search and "Start a project" — none of which a member of
 * staff clearing a report queue wants. A creator is not staff. Every one of those four is
 * somewhere they might reasonably go next, and "Start a project" is the action this whole
 * surface exists to complete.
 *
 * <h2>Two landmarks this change had to take away, not add</h2>
 *
 * **One `<main>`.** `SiteShell` owns it and it is the skip link's target; all six tabs
 * declared one of their own, so all six gave it up. Two is not a duplicated landmark so much
 * as an ambiguous one.
 *
 * **One `banner`.** `EditorShell` drew the campaign's title and state inside a `<header>`.
 * By HTML-AAM that element is generic once it descends from `main`, which it now does — but
 * that is a claim about every consumer of the accessibility tree and it does not hold
 * uniformly, so it is a `<div>` since this change. `EditorShell`'s own comment carries the
 * detail. A page announcing two site headers tells somebody there are two to choose between.
 *
 * Neither could be deferred: adding the shell above the editor without both edits would have
 * produced two mains and two banners at once, which is why they are one change.
 */
export default function CampaignEditorLayout({ children }: { children: ReactNode }) {
  return <SiteShell>{children}</SiteShell>;
}
