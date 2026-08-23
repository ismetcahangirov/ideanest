/**
 * What the header and the footer point at — §4.13 WS-01 and WS-02, stated once.
 *
 * <h2>It lists what exists, and nothing else</h2>
 *
 * The rule `DashboardNav` already states, and it matters more here because this is on every
 * page: an entry pointing at a 404 tells a visitor the site is broken, and one that is
 * disabled tells them it is unfinished. §4.13 names nine capabilities. About, How it works and
 * Trust and safety (WS-07) were absent until #292 built them and are in the footer now; the
 * legal pages (WS-08, #293) are still **not**, because that issue is blocked on a legal
 * deliverable §22 owns. They arrive with their pages.
 *
 * `apps/web/README.md` carries the route table this is derived from. When a route lands, it
 * is added here — one file, both surfaces, no chance of the footer knowing about a page the
 * header does not.
 *
 * <h2>Two lists, not one</h2>
 *
 * The header's is short because §8.6 gives it a 445-pixel pill to live in when collapsed;
 * the footer's is grouped because a footer is where somebody looks for the thing that was
 * not worth a slot in the header. They are separate data rather than one list with flags,
 * because "which of these is in the header" is a design decision that changes, and a flag
 * would put that decision in the wrong file.
 */

export interface NavigationLink {
  readonly href: string;
  readonly label: string;
}

/**
 * The primary navigation — the pill in the middle of the header, and the top of the mobile
 * drawer.
 *
 * TWO ENTRIES, AND THAT IS THE POINT. Discovery is the front door and the categories are the
 * indexable way into it (WS-05); everything else a visitor wants is either behind the search
 * field beside these or is an account surface, which lives in the actions on the right. A
 * header that grew a third and a fourth entry would be a header that no longer collapses to
 * the width §8.6 specifies.
 */
export const PRIMARY_NAVIGATION: readonly NavigationLink[] = Object.freeze([
  { href: '/discover', label: 'Discover' },
  { href: '/categories', label: 'Categories' },
]);

export interface FooterGroup {
  readonly heading: string;
  readonly links: readonly NavigationLink[];
}

/**
 * The footer's columns.
 *
 * Four, not the three there were before #292 and not the four §8.6's sketch draws. The fourth
 * here is **About** — WS-07's three static pages — and the column §8.6 draws is Legal, which
 * is still deliberately absent: §22 owns that copy, #293 is `status: needs-decision` on it,
 * and a Terms link that resolves to nothing is worse than no Terms link at all — it is a
 * promise about a document that does not exist.
 *
 * The account column gained the four screens #288, #289, #290 and #279 built. The footer is
 * where somebody looks for the thing that was not worth a slot in the header, and an account
 * area with eight destinations and no entry point outside its own navigation is an account
 * area reachable only by people who already know where it is.
 */
export const FOOTER_GROUPS: readonly FooterGroup[] = Object.freeze([
  {
    heading: 'Explore',
    links: [
      { href: '/discover', label: 'Discover' },
      { href: '/categories', label: 'Categories' },
      { href: '/search', label: 'Search' },
    ],
  },
  {
    heading: 'Creators',
    links: [{ href: '/projects/new', label: 'Start a campaign' }],
  },
  {
    heading: 'Your account',
    links: [
      { href: '/notifications', label: 'Notifications' },
      { href: '/account/saved', label: 'Saved projects' },
      { href: '/account/surveys', label: 'Surveys' },
      { href: '/account/deliveries', label: 'Deliveries' },
      { href: '/settings/notifications', label: 'Settings' },
    ],
  },
  {
    heading: 'About',
    links: [
      { href: '/about', label: 'About IdeaNest' },
      { href: '/how-it-works', label: 'How it works' },
      { href: '/trust-safety', label: 'Trust and safety' },
    ],
  },
]);

/**
 * Whether a link is the page being read.
 *
 * A PREFIX FOR EVERYTHING BUT THE ROOT. `/categories` is current on
 * `/categories/games/prints`, because a reader on a subcategory is inside Categories and a
 * navigation that says otherwise is a navigation that has lost them. `/` is exact, or it
 * would be current everywhere.
 *
 * This decides `aria-current="page"`, which is the carrier — the underline is the second,
 * visual signal for the same fact, because docs/ui-kit.md §9.2 forbids colour alone.
 */
export function isCurrent(href: string, pathname: string): boolean {
  if (href === '/') return pathname === '/';
  return pathname === href || pathname.startsWith(`${href}/`);
}
