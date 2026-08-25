import type { ReactNode } from 'react';
import { SiteShell } from '../../components/shell/SiteShell';

/**
 * The public site's route group — §4.13 WS-01 and WS-02, issue #260.
 *
 * <h2>What a route group is doing here</h2>
 *
 * `(site)` adds nothing to any URL: `/discover` is still `/discover`. What it adds is a
 * layout, and therefore a header and a footer, to every route filed under it — which is the
 * whole of #260. Before it, twenty routes shipped with no shared chrome because there was
 * nowhere for shared chrome to live.
 *
 * <h2>Which routes are in it today, and which are not</h2>
 *
 * In: the home page, discovery, search, and the category landing pages — everything this
 * pull request builds, plus `/discover`, which moved here.
 *
 * **Not in it: the checkout, the campaign editor, the creator dashboard, the settings screens
 * and the admin console.** They are not all left out because they should have no chrome;
 * several of them should. They are left out because moving them is not free and is not this
 * issue's:
 *
 *   - The checkout must not get this header at all. §8.5 makes it the one screen a white
 *     panel dominates and §5 gives it a motion budget of near zero; a collapsing navigation
 *     bar offering to take somebody to Discover, on the screen where they are about to
 *     pledge, is the opposite of both.
 *   - The admin console gets its own shell, which is #294.
 *
 * **A route does not have to be filed here to get the shell, and three are not.** The public
 * profile (`app/u/layout.tsx`, #274), the campaign page and the pre-launch page
 * (`app/projects/[id]/[projectSlug]/layout.tsx` and its sibling, #343) each render
 * `SiteShell` from a layout at their own segment. All three sit under a dynamic parent shared
 * with private routes — `/projects/[id]` carries `/edit`, `/dashboard` and `/back` — and Next
 * allows one slug name per level, so lifting the public half into this group would mean
 * restructuring the private half with it. A leaf layout buys the same chrome and leaves the
 * siblings alone, which is what keeps the checkout out of the header by default rather than
 * by exception.
 *
 * `apps/web/README.md`'s route table records which routes carry the shell and how, so the
 * arrangement is written down rather than left to be noticed.
 *
 * <h2>No metadata here</h2>
 *
 * The root layout already carries the site defaults, the title template, and the Open Graph
 * block every route inherits (`lib/seo/metadata.ts`). A second declaration at this level
 * would be a second place for the site name to be spelled.
 */
export default function SiteLayout({ children }: { children: ReactNode }) {
  return <SiteShell>{children}</SiteShell>;
}
