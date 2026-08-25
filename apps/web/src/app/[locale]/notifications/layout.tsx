import type { ReactNode } from 'react';
import { SiteShell } from '../../../components/shell/SiteShell';

/**
 * The frame around the in-app inbox — §4.13 WS-01 and WS-02, issue #345.
 *
 * <h2>Why this file exists</h2>
 *
 * Until it did, `/notifications` rendered with no header and no footer. That is worse here
 * than the bare fact suggests: **this page is reached from the header.** `SiteHeader` draws a
 * bell that links to it, and so do `AccountMenu` and `MobileNavDrawer`. A reader pressed a
 * control in the header, arrived at the inbox, and the header was gone — which reads as
 * having left the site rather than as having opened a page on it.
 *
 * Same root cause as #343 and the same shape of fix: a four-line layout at the segment,
 * following `app/u/layout.tsx`.
 *
 * <h2>`SiteShell` and not `AccountArea`</h2>
 *
 * The account screens share a frame that adds a rail over the thirteen destinations somebody
 * manages about themselves. This is not one of them, and the page's own docblock is the
 * argument: "At `/notifications` rather than under `/settings`, because this is not a
 * setting — it is a place somebody reads."
 *
 * That is not only a naming preference. `/notifications` is not in `ACCOUNT_GROUPS`, so
 * `AccountArea` would draw the rail with **no entry marked `aria-current="page"`** — a
 * navigation that tells the reader they are nowhere in it. Putting the route into that list
 * to fix the symptom would need a label in all four message catalogues (#324's) and would
 * contradict the argument the page makes for its own URL.
 *
 * So it takes the frame the header belongs to, because the header is where it is reached
 * from.
 *
 * <h2>No `<main>` here, and the page gave up its own</h2>
 *
 * `SiteShell` owns the only `<main>` on the document and it is the skip link's target. The
 * page below is a `<div>` now, for the same reason `/settings/sessions` and
 * `/settings/notifications` each lost one in #275: two `<main>` elements is not a duplicated
 * landmark so much as an ambiguous one.
 */
export default function NotificationsLayout({ children }: { children: ReactNode }) {
  return <SiteShell>{children}</SiteShell>;
}
