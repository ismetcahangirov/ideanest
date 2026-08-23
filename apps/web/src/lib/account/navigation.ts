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
 * account navigation is exactly where somebody adds the entry before the page.
 *
 * **The two credentials arrived with #277 and are two entries rather than one.** A-12 and A-13
 * are one screen's worth of decisions to the service — `AccountCredentialsService` holds both
 * — and they are two here because their consequences differ in the one way a reader cares
 * about: changing the password ends every session on the account, and changing the address ends
 * none. One entry called "Sign-in details" would put one warning over both, and the warning is
 * either wrong for half the page or absent where it is needed.
 *
 * **Profile arrived with #276, and it is the entry this comment used to argue against.** The
 * argument was that the service had no write to save a name or a biography to, so an editor
 * would be a form with nowhere to send anything — and it was correct while it held. It does
 * not any more: `GET /v1/me/profile` and `PATCH /v1/me/profile` are §4.2's P-01 to P-03, and
 * `/settings/profile` is a page rather than a promise.
 *
 * It is **not** `PATCH /v1/me`, which is what that paragraph asked for and what
 * `OwnProfileController` declines to build: a patch over the whole account is a surface every
 * future column joins by default, and the first one added without thinking becomes writable
 * by anybody holding a token. The endpoint names one thing and can only ever change that
 * thing. The entry sits above **Notifications** because it is the only one in this group
 * about what other people see; everything under it is about what the account is told, who is
 * signed in to it, and what happens to its data.
 *
 * P-01's **upload and crop** are still absent from that page and the page says so: there is
 * no object storage and no media table, so the picture is the address of something already
 * published (§13.1). That is a missing half of one field rather than a page that cannot work,
 * which is the line this list draws.
 *
 * One §4.2 capability is still deliberately absent:
 *
 *   - **Language and currency** (P-10, #280) — blocked on §21.1's localisation work, and
 *     `SiteFooter` already refuses to draw a control that would change nothing.
 *
 * It arrives with its page.
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
      /*
       * FIRST, AND NOT UNDER `/account/*` LIKE THE FOUR BELOW IT — #287.
       *
       * The prefix split this navigation is built on (`/settings/*` for what somebody
       * decides, `/account/*` for what they have) would put the pledge list under
       * `/account`, and it is not there because `/pledges/{id}/address` has been a real
       * URL since #75 and is linked from survey and delivery email. Moving the parent to
       * buy a tidier tree would either break those links or leave one screen addressed two
       * ways; §4.2's own note about `/settings/notifications` makes the same argument.
       *
       * First in the group because it is the only entry about money. What somebody has
       * committed outranks what they have saved to look at later.
       */
      {
        href: '/pledges',
        label: 'Pledges',
        summary: 'What you have backed, and what you can still change.',
      },
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
      /*
       * FIRST IN THE GROUP — #276. It is the only entry here about what strangers see; the
       * five below it are about what the account is told, which devices are in, and what
       * happens to its data. The visibility switch that decides whether the profile answers
       * at all stays on "Data and closure" and `ProfileVisibilityPanel` explains why.
       */
      {
        href: '/settings/profile',
        label: 'Profile',
        summary: 'Your name, picture, biography and links, as everybody else sees them.',
      },
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
        href: '/settings/email',
        label: 'Email address',
        summary: 'The address you sign in with, and where we write to you.',
      },
      {
        href: '/settings/password',
        label: 'Password',
        summary: 'Change it here, or reset it by email if you cannot.',
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
