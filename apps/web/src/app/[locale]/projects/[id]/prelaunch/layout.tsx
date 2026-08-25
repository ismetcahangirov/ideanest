import type { ReactNode } from 'react';
import { SiteShell } from '../../../../../components/shell/SiteShell';

/**
 * The frame around the public pre-launch page — §4.13 WS-01 and WS-02, issue #343.
 *
 * The sibling of `app/projects/[id]/[projectSlug]/layout.tsx`, and that file carries the
 * argument for both: a leaf layout puts this route in the site shell without touching
 * `/edit`, `/dashboard` or `/back`, and `/back` is the one that must not be touched.
 *
 * It is the same argument with more force here, if anything. The page's own comment says why
 * the route exists outside `/edit` at all — **"this is a link that goes into a social post"**
 * — so a stranger with no other context is the expected reader, and until now the page they
 * landed on offered a follow button and nothing else. Not even a way to the campaign's
 * category, or to the platform's own name.
 *
 * No `<main>` here, and the page gave up its own: `SiteShell` owns the only one, and
 * `PrelaunchView` already draws its own 720-pixel column and padding, so the wrapper it lost
 * was a landmark rather than a layout.
 */
export default function PrelaunchPageLayout({ children }: { children: ReactNode }) {
  return <SiteShell>{children}</SiteShell>;
}
