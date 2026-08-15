'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { Tag, cn } from '@ideanest/ui';
import type { ProjectState } from '../../lib/projects/api';
import { EDITOR_TABS, editorTabHref, type EditorTabKey } from './tabs';

/**
 * The frame every editor tab renders inside: who the project is, what state it
 * is in, whether the work is saved, and how to get to the other sections.
 *
 * IT IS NAVIGATION, NOT A TAB WIDGET. Each section is a route, so the controls
 * are links in a `<nav>` with `aria-current="page"`, not `role="tablist"`.
 * ARIA tabs promise same-document panels and take over the arrow keys; used over
 * routing they break the browser's own controls — the back button, "open in a
 * new tab", and a copyable address for the section somebody is looking at. Tab
 * and Enter already work, and they work the way the rest of the web does.
 *
 * The links are not `Chip` either, even though they wear its clothes: `Chip` is
 * a button carrying `aria-pressed`, which describes a toggle rather than the
 * page you are on.
 *
 * MOTION: none. `docs/motion-system.md` §5 gives the campaign editor "none —
 * autosave indicator only", so the only animation on this surface is the one
 * inside `SaveStatus`. Colour changes take the 150ms the rest of the system
 * uses; nothing enters, nothing fades up.
 */

/**
 * The sixteen states of docs/architecture.md §6.1 as words a creator can read.
 *
 * Exported because every editor surface has to name the state, and two
 * translations of `CHANGES_REQUESTED` would eventually disagree.
 */
export const PROJECT_STATE_LABEL: Record<ProjectState, string> = {
  DRAFT: 'Draft',
  PRELAUNCH: 'Pre-launch',
  SUBMITTED: 'In review',
  CHANGES_REQUESTED: 'Changes requested',
  REJECTED: 'Rejected',
  APPROVED: 'Approved',
  SCHEDULED: 'Scheduled',
  LIVE: 'Live',
  SUSPENDED: 'Suspended',
  CANCELED: 'Canceled',
  SUCCESSFUL: 'Funded',
  UNSUCCESSFUL: 'Not funded',
  COLLECTING: 'Collecting',
  LATE_PLEDGE: 'Late pledges',
  FULFILLING: 'Fulfilling',
  COMPLETED: 'Completed',
};

const TAB_BASE = [
  'inline-flex h-[34px] shrink-0 items-center gap-1.5 whitespace-nowrap',
  'rounded-full border px-4 text-[13px] font-medium',
  'transition-[background-color,color,border-color] duration-150 ease-in-out',
];

export interface EditorShellProps {
  projectId: string;
  /** Which tab the surrounding route is. */
  active: EditorTabKey;
  /** The project's title, once it has loaded. */
  title?: string | null;
  state?: ProjectState | null;
  /**
   * The save indicator, supplied by the tab.
   *
   * The shell does not own it: each tab saves its own fields through its own
   * `useAutosave`, and a shell that held the state would have to know about
   * every form inside it.
   */
  status?: ReactNode;
  children: ReactNode;
}

export function EditorShell({
  projectId,
  active,
  title,
  state,
  status,
  children,
}: EditorShellProps) {
  return (
    <div className="mx-auto w-full max-w-[880px] px-5 py-10 sm:px-6 sm:py-14">
      <header className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <p className="text-xs font-medium tracking-[0.06em] text-white/40 uppercase">
            Campaign editor
          </p>
          <h1 className="mt-1 text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
            {/* An untitled project is impossible — creation requires a title —
                but a project still loading has no title to show yet. */}
            {title ?? 'Loading'}
          </h1>
        </div>

        <div className="flex items-center gap-3">
          {state != null && <Tag>{PROJECT_STATE_LABEL[state]}</Tag>}
          {status}
        </div>
      </header>

      <nav aria-label="Campaign sections" className="mt-7">
        <ul className="scrollbar-none flex gap-2 overflow-x-auto">
          {EDITOR_TABS.map((tab) => {
            const current = tab.key === active;

            if (!tab.available) {
              /*
               * Focusable on purpose. A section that exists but is not finished
               * is worth knowing about, and `disabled` would take it out of the
               * tab order entirely — a keyboard user would simply never learn it
               * was there. `aria-disabled` announces the state and the button
               * does nothing, which is the honest combination.
               */
              return (
                <li key={tab.key}>
                  <button
                    type="button"
                    aria-disabled="true"
                    className={cn(
                      TAB_BASE,
                      'cursor-default border-white/8 bg-surface-2 text-white/64',
                    )}
                  >
                    {tab.label}
                    {/*
                      Visible cue and spoken cue, saying the same thing. The
                      hidden half keeps the accessible name honest — it contains
                      the visible label rather than replacing it.

                      The word is what marks the tab as unavailable, not a
                      dimmer grey: --text-disabled is 2.6:1 and prohibited for
                      text (docs/ui-kit.md §9.2), and dimness alone would be
                      colour carrying meaning by itself.
                    */}
                    <span aria-hidden="true" className="text-white/40">
                      soon
                    </span>
                    {/* The comma is load-bearing. An accessible name is the
                        concatenation of its parts with each part trimmed and no
                        separator inserted, so a leading space would be dropped
                        and the name would read "Rewardsnot available yet". */}
                    <span className="sr-only">, not available yet</span>
                  </button>
                </li>
              );
            }

            return (
              <li key={tab.key}>
                <Link
                  href={editorTabHref(projectId, tab)}
                  aria-current={current ? 'page' : undefined}
                  className={cn(
                    TAB_BASE,
                    // White for "you are here", never lime: a section is not
                    // urgent (docs/ui-kit.md §7.3).
                    current
                      ? 'border-transparent bg-white text-on-white'
                      : 'border-white/8 bg-surface-2 text-white/64 hover:bg-surface-3 hover:text-white',
                  )}
                >
                  {tab.label}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>

      <div className="mt-8">{children}</div>
    </div>
  );
}
