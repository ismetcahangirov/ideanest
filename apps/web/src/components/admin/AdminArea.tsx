import Link from 'next/link';
import type { ReactNode } from 'react';
import { MAIN_CONTENT_ID, SkipLink } from '../shell/SkipLink';
import { AdminNav } from './AdminNav';

/**
 * The frame every console screen renders inside — §4.11 and §4.13 WS-01, issue #294.
 *
 * <h2>Its own shell, and not the site's</h2>
 *
 * `app/(site)/layout.tsx` said this was coming and said why in one line: "the admin console
 * gets its own shell". Two reasons, and the second is the one worth measuring.
 *
 * <strong>The public header is the wrong chrome.</strong> It offers Discover, the categories,
 * search, "Start a project" and an account menu. A member of staff clearing a report queue
 * wants none of those, and a header whose most prominent control invites them to start a
 * campaign is a header designed for somebody else.
 *
 * <strong>And it is not free.</strong> `MinimalShell` records the measurement:
 * `SiteHeader` reaches `@ideanest/ui`'s root barrel for `TopBar`, `useDismiss` and
 * `useFocusTrap`, and a barrel in a `transpilePackages` source package lands in one shared
 * chunk — 83.3 KiB of it, the last time somebody put `SiteShell` somewhere it did not
 * belong. The console has eleven routes and would have paid that eleven times over for a
 * navigation bar it does not use.
 *
 * <h2>Why not `MinimalShell`, then</h2>
 *
 * Because a console is not a sign-in page. `MinimalShell` is a wordmark and a `<main>`, which
 * is right for a screen with one job and wrong for a surface somebody moves around inside
 * several times in a sitting. What this adds over it is exactly the rail, and the rail is
 * the whole of #294.
 *
 * <h2>One `<main>`, and it is here</h2>
 *
 * The two screens that existed before this each declared their own, because there was no
 * shell to be inside; both lost it in the same change that added this file. Two `<main>`
 * elements is not a duplicated landmark so much as an ambiguous one — assistive technology
 * offers "jump to main" and there is now more than one answer. `AccountArea` made the same
 * correction to `/settings/sessions` and `/settings/notifications`.
 *
 * <h2>The rail is beside the content, not above it</h2>
 *
 * docs/ui-kit.md §6.3 puts a rail on the left of a working surface, and a console is the
 * definition of one: ten destinations somebody moves between while holding one complaint in
 * their head. Above the content it would push every screen down and would compete with the
 * bar a few pixels above it.
 *
 * <h2>Nothing here says who may be looking</h2>
 *
 * <strong>The route is not a gate, and must not become one.</strong> There is no role model
 * in the schema or in the access token until epic #100, so every endpoint the console calls
 * refuses a caller who is not on the configured moderator list, and each screen renders that
 * refusal. A check here would be a second, weaker copy of one the service already makes
 * correctly, and the two would eventually disagree — the dangerous direction being the one
 * where this file says yes. The two screens that predate the console carry the same note,
 * and #295 is the issue that replaces the list with something a client could honestly read.
 *
 * <h2>Motion</h2>
 *
 * None. docs/motion-system.md §5 gives an administrative surface the same budget as account
 * settings — 150ms of colour on a control, and nothing that moves.
 */
export interface AdminAreaProps {
  readonly children: ReactNode;
}

export function AdminArea({ children }: AdminAreaProps) {
  return (
    <div className="relative flex min-h-dvh flex-col">
      <SkipLink />

      {/*
        A bar rather than a header component: two links and a label. It is deliberately not
        `TopBar` from the kit — see the docblock on what importing the barrel costs a route
        that draws no navigation.
      */}
      <div className="border-b border-white/8 bg-surface-1">
        <div className="mx-auto flex w-full max-w-[1280px] items-center justify-between gap-4 px-5 py-4 sm:px-6">
          <Link
            href="/admin"
            className="rounded-lg text-sm font-semibold tracking-[-0.01em] text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            IdeaNest{' '}
            <span className="font-normal text-white/48">Console</span>
          </Link>

          {/*
            The way out. A console with no link back to the platform is one somebody leaves
            by editing the address bar, and the screens here are read alongside the pages
            they are about.
          */}
          <Link
            href="/"
            className="rounded-lg text-sm text-white/64 transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            Back to the site
          </Link>
        </div>
      </div>

      <main
        id={MAIN_CONTENT_ID}
        tabIndex={-1}
        className="mx-auto w-full max-w-[1280px] flex-1 px-5 py-10 focus:outline-none sm:px-6 sm:py-12"
      >
        <div className="flex flex-col gap-10 lg:flex-row lg:gap-14">
          <div className="lg:w-[15rem] lg:shrink-0">
            <AdminNav />
          </div>
          {/*
            `min-w-0` so a ledger table or a provider reference scrolls inside its own
            container rather than widening the flex row and pushing the rail off the side of
            the page.
          */}
          <div className="min-w-0 flex-1">{children}</div>
        </div>
      </main>
    </div>
  );
}
