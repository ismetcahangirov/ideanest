import type { ReactNode } from 'react';
import { SiteShell } from '../../components/shell/SiteShell';

/**
 * The frame around `/u/{slug}` — §4.2 and §4.13 WS-01, issue #274.
 *
 * <h2>A layout rather than a place in `app/(site)`</h2>
 *
 * The public profile belongs in the site shell for every reason `app/(site)/layout.tsx` gives:
 * it is a public page a stranger arrives at from a campaign, and taking the header away would
 * take away the way onward. It is not filed inside that route group because `/u/**` is this
 * pull request's, and moving a route between groups is a change to a directory three other
 * pieces of work are editing at the same time. One layout of four lines buys the same chrome
 * without the collision.
 *
 * <h2>What it also buys: the right 404</h2>
 *
 * `app/u/not-found.tsx` renders inside this layout, so a `notFound()` from the profile page —
 * which is what an unknown slug, a closed account and a private profile all produce — lands
 * on a full-shell failure state rather than on the root's minimal one. That matters here more
 * than usual: a reader who followed a stale link to somebody's profile is exactly the reader
 * who wants the search field and the categories on hand.
 *
 * No `<main>` here. `SiteShell` owns the only one on the page, and a second landmark would
 * make "jump to main" a question with two answers.
 */
export default function ProfileLayout({ children }: { children: ReactNode }) {
  return <SiteShell>{children}</SiteShell>;
}
