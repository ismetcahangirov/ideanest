/**
 * The account area's navigation — §4.2, issue #275.
 *
 * <h2>Why this exists at all</h2>
 *
 * `/settings/notifications` and `/settings/sessions` shipped with no shared chrome and no way
 * to reach one from the other. Each was a page somebody arrived at from an email or from the
 * footer and left by pressing Back. #275's own sentence for it — "the navigation the existing
 * notification and session screens should already have been inside" — is the whole issue.
 *
 * <h2>Two groups, one list</h2>
 *
 * Everything a signed-in person manages about themselves is here, split by the question being
 * asked rather than by the URL prefix:
 *
 *   - **Your account** is what somebody *has* — the campaigns they saved, the creators they
 *     follow, the surveys they owe an answer to, the parcels on their way.
 *   - **Settings** is what somebody *decides* — what they are told, which devices are in,
 *     whether a second factor is required, and what happens to their data.
 *
 * They live under `/account` and `/settings` respectively, and that split is a fact about the
 * existing URLs rather than a design: `/settings/notifications` is the address in every
 * notification email the platform has ever sent, and moving it to buy a tidier prefix would
 * break links this repository does not own.
 *
 * <h2>It lists what exists, and nothing else</h2>
 *
 * `components/shell/navigation.ts`'s rule, restated because it is easy to break here — an
 * account navigation is exactly where somebody adds the entry before the page. Two §4.2
 * capabilities are deliberately absent:
 *
 *   - **Profile** (P-01 to P-03, #276) — the service has no write for it. There is no
 *     `PATCH /v1/me`, so an editor would be a form with nowhere to save.
 *   - **Language and currency** (P-10, #280) — blocked on §21.1's localisation work, and
 *     `SiteFooter` already refuses to draw a control that would change nothing.
 *
 * They arrive with their pages.
 */

export interface AccountLink {
  readonly href: string;
  readonly label: string;
  /** One line under the label, for the landing page. The navigation itself shows only labels. */
  readonly summary: string;
}

export interface AccountGroup {
  readonly heading: string;
  readonly links: readonly AccountLink[];
}

export const ACCOUNT_GROUPS: readonly AccountGroup[] = Object.freeze([
  {
    heading: 'Your account',
    links: [
      {
        href: '/account/saved',
        label: 'Saved projects',
        summary: 'Campaigns you saved to come back to.',
      },
      {
        href: '/account/following',
        label: 'Following',
        summary: 'Creators whose launches you are told about.',
      },
      {
        href: '/account/surveys',
        label: 'Surveys',
        summary: 'What creators still need from you before they can ship.',
      },
      {
        href: '/account/deliveries',
        label: 'Deliveries',
        summary: 'Where each reward is, and where it is going.',
      },
    ],
  },
  {
    heading: 'Settings',
    links: [
      {
        href: '/settings/notifications',
        label: 'Notifications',
        summary: 'What IdeaNest tells you, and how it reaches you.',
      },
      {
        href: '/settings/sessions',
        label: 'Devices',
        summary: 'Every browser signed in to this account.',
      },
      {
        href: '/settings/security',
        label: 'Two-factor authentication',
        summary: 'A code from your phone, on top of your password.',
      },
      {
        href: '/settings/privacy',
        label: 'Data and closure',
        summary: 'Take a copy of your data, or close the account.',
      },
    ],
  },
]);

/** Every link in both groups, flattened — the landing page and the tests read this. */
export const ACCOUNT_LINKS: readonly AccountLink[] = Object.freeze(
  ACCOUNT_GROUPS.flatMap((group) => group.links),
);

/**
 * Whether a link is the page being read.
 *
 * EXACT, unlike the site header's prefix match. Every entry here is a leaf: nothing sits
 * under `/settings/security`, and a prefix match would make `/account/saved` current on a
 * hypothetical `/account/saved-something` for no gain. `aria-current="page"` is the carrier;
 * the visual treatment is the second signal, because docs/ui-kit.md §9.2 forbids colour
 * alone.
 */
export function isCurrentAccountLink(href: string, pathname: string): boolean {
  return pathname === href;
}

/** The heading for a path, or `null` where the path is not one of ours. */
export function accountLinkFor(pathname: string): AccountLink | null {
  return ACCOUNT_LINKS.find((link) => link.href === pathname) ?? null;
}
