import type { ReactNode } from 'react';
import { SiteShell } from '../../../components/shell/SiteShell';

/**
 * The frame around "name a campaign and create the draft" — §4.13 WS-01 and WS-02, issue #347.
 *
 * The sibling of `app/projects/[id]/edit/layout.tsx`, and that file carries the argument.
 * This route is the editor's first screen in every practical sense — its form lives in
 * `components/campaign-editor/`, and submitting it redirects straight to
 * `/projects/{id}/edit/basics`. Giving the editor a header and not this one would mean the
 * chrome appears halfway through a single flow, which reads as having changed sites rather
 * than as having advanced a step.
 *
 * It is also reached from inside the shell itself — "Start a campaign" in `AccountMenu` and
 * in `MobileNavDrawer`, both of which the header owns — so the objection #345 raised about
 * the notifications bell applies here too: a control in the header that takes the header
 * away.
 *
 * No `<main>` here, and the page gave up its own — `SiteShell` owns the only one.
 */
export default function NewProjectLayout({ children }: { children: ReactNode }) {
  return <SiteShell>{children}</SiteShell>;
}
