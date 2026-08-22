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
 * **Not in it, and deliberately: the campaign page, the pre-launch page, the checkout, the
 * campaign editor, the creator dashboard, the settings screens and the admin console.** They
 * are not left out because they should have no chrome; several of them should. They are left
 * out because moving them is not free and is not this issue's:
 *
 *   - `/projects/[id]/[projectSlug]` and `/projects/[id]/prelaunch` sit under a dynamic
 *     segment that also carries `/projects/[id]/edit`, `/dashboard` and `/back`. Next allows
 *     one slug name per level, so the public half cannot be lifted into this group without
 *     restructuring the private half with it, and each of those routes has an issue that
 *     owns its layout. #281 is the one that rebuilds the campaign page's header.
 *   - The checkout must not get this header at all. §8.5 makes it the one screen a white
 *     panel dominates and §5 gives it a motion budget of near zero; a collapsing navigation
 *     bar offering to take somebody to Discover, on the screen where they are about to
 *     pledge, is the opposite of both.
 *   - The admin console gets its own shell, which is #294.
 *
 * `apps/web/README.md`'s route table records which routes carry the shell, so the gap is
 * written down rather than left to be noticed.
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
