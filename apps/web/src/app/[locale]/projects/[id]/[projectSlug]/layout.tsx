import type { ReactNode } from 'react';
import { SiteShell } from '../../../../../components/shell/SiteShell';

/**
 * The frame around the public campaign page — §4.13 WS-01 and WS-02, issue #343.
 *
 * <h2>Why this file exists at all</h2>
 *
 * Until it did, `/projects/{creatorSlug}/{projectSlug}` rendered with no header and no
 * footer. The layout chain was `app/layout.tsx` — which contributes `<WebVitals />` and
 * `<SessionProvider>`, and no chrome — straight to the page. On the single most-shared
 * address on the platform, a reader who arrived from a link, a search result or an unfurl
 * had no navigation, no search field, no account menu and no way onward but the back button.
 *
 * <h2>A layout here rather than a place in `app/(site)`</h2>
 *
 * `apps/web/README.md` recorded this gap and gave a reason for it: this route cannot be
 * lifted into the `(site)` route group, because Next allows one slug name per dynamic level
 * and `[id]` is shared with `/edit`, `/dashboard` and `/back` — so moving the public half
 * means restructuring the private half with it.
 *
 * That reason is sound and it was never the only route to the shell. `app/u/layout.tsx` has
 * put `/u/{slug}` inside `SiteShell` from four lines at its own segment since #274, and the
 * same four lines work here. A leaf layout applies to this segment and to nothing beside it,
 * which is not merely convenient — it is the property this particular subtree needs, because
 * **`/projects/[id]/back` must not get this header**. `docs/ui-kit.md` §8.5 makes the
 * checkout the one screen a white panel dominates and `docs/motion-system.md` §5 gives it a
 * motion budget of near zero; a collapsing navigation bar offering a trip to Discover, on the
 * screen where somebody is about to pledge, is the opposite of both. Filing the public page
 * into a group would have put the burden of proof on keeping that route out. This puts it on
 * letting a route in.
 *
 * <h2>No `<main>` here, and the page gave up its own</h2>
 *
 * `SiteShell` owns the only `<main>` on the page and it is the skip link's target. The page
 * below used to declare one; it is a `<div>` now, for the reason `SiteShell` states —
 * two `<main>` elements is not a duplicated landmark so much as an ambiguous one, and
 * "jump to main" becomes a question with two answers.
 *
 * <h2>What this costs the route #119 exists to keep fast</h2>
 *
 * The header and footer are the same chunks every other public page already loads, so the
 * cost is the shell's own graph and not a second copy of anything. The measured figure is in
 * `apps/web/performance/budgets.json`, which CI fails on in both directions.
 */
export default function CampaignPageLayout({ children }: { children: ReactNode }) {
  return <SiteShell>{children}</SiteShell>;
}
