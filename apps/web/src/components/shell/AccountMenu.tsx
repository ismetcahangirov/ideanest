'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from '../../i18n/navigation';
import { usePathname } from '../../i18n/navigation';
import { ChevronDown } from 'lucide-react';
import { Avatar, cn, useDismiss } from '@ideanest/ui';
import type { Session } from '../../lib/session/session';

/**
 * The signed-in reader's own corner of the header — §4.13 WS-01's "signed-in action pair".
 *
 * <h2>Built from the kit's hooks rather than from `Popover`</h2>
 *
 * `Popover` lives behind `@ideanest/ui/motion`, and this component is in the shell — on
 * every route in the site. Importing it would put 116 kB of animation runtime into the first
 * load of every page in the application, which is the exact regression `packages/ui/src/motion.ts`
 * was written to undo. So the panel is markup and CSS, and the two behaviours that actually
 * matter come from hooks that carry no such cost: `useDismiss` for Escape, and a pointer
 * listener for a click outside.
 *
 * It also costs nothing to leave out the animation, because it is not allowed one:
 * docs/motion-system.md §5 gives the shell §4.7's collapse and nothing else, and §5.1's rule
 * about panels — "a panel that animates while somebody is using it is a panel that is slower
 * to use" — applies to a menu more than to anything.
 *
 * <h2>What it is, in accessibility terms</h2>
 *
 * A disclosure, not a `menu`. The ARIA menu pattern brings arrow-key roving, `menuitem`
 * roles and the expectation that Tab leaves the whole widget, and it is meant for
 * application commands rather than for a short list of links. A button with
 * `aria-expanded`/`aria-controls` revealing a list of ordinary links is what this is, it
 * behaves the way the browser already behaves, and every row in it stays a real link that
 * can be opened in a new tab.
 *
 * The panel closes on Escape, on a click outside, and on a navigation — the last because
 * Next routes without unmounting the shell, so a menu left open would follow the reader onto
 * the page they just chose.
 */

export interface AccountMenuProps {
  readonly session: Session;
  readonly onSignOut: () => Promise<void> | void;
}

const ROW =
  'block rounded-sm px-3 py-2.5 text-sm text-white/64 transition-colors duration-150 ease-in-out hover:bg-surface-3 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]';

export function AccountMenu({ session, onSignOut }: AccountMenuProps) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);

  const close = useCallback(() => setOpen(false), []);
  useDismiss({ open, onDismiss: close });

  // A navigation is a dismissal. The shell does not unmount between routes, so without this
  // the panel would still be open over whatever the reader chose.
  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!open) return;

    function onPointerDown(event: PointerEvent) {
      const node = container.current;
      if (node !== null && event.target instanceof Node && !node.contains(event.target)) {
        setOpen(false);
      }
    }

    /*
     * `pointerdown` rather than `click`, and on the document rather than on a backdrop.
     * There is no backdrop — a menu this small must not put a blocking layer over the page —
     * and `click` fires after focus has already moved, which makes the panel close a frame
     * after the thing under it has been pressed.
     */
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [open]);

  return (
    <div ref={container} className="relative">
      <button
        type="button"
        aria-expanded={open}
        aria-controls="account-menu"
        onClick={() => setOpen((current) => !current)}
        className={cn(
          'inline-flex h-10 items-center gap-2 rounded-full pl-1 pr-2.5',
          'text-sm font-medium text-white transition-colors duration-150 ease-in-out',
          'hover:bg-surface-3 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]',
          open && 'bg-surface-3',
        )}
      >
        {/*
          THE AVATAR IS DECORATIVE HERE and the name beside it is not. `Avatar` labels itself
          with the name, which is right when it stands alone; inside a control that already
          says the name it would be announced twice. §9.4 asks for an accessible name on an
          icon-only control, and the answer is to make it not icon-only rather than to label
          it twice. The name is always rendered for the same reason — initials are ambiguous
          for two people who share them, at every viewport width.
        */}
        <span aria-hidden="true">
          <Avatar name={session.name} size="sm" />
        </span>
        <span className="max-w-[8ch] truncate sm:max-w-[12ch]">{session.name}</span>
        <ChevronDown aria-hidden="true" className="size-4 text-white/40" />
      </button>

      {open && (
        <div
          id="account-menu"
          className="absolute right-0 top-[calc(100%+8px)] z-50 w-[248px] rounded-md border border-white/8 bg-surface-2 p-2 shadow-[var(--shadow-panel)]"
        >
          <p className="truncate px-3 pb-2 pt-1 text-xs text-white/40" title={session.email}>
            {session.email}
          </p>

          {/*
            THE UNVERIFIED STATE IS SAID HERE AND NOWHERE ELSE IN THE SHELL. §4.1 A-01 makes
            verification required, and somebody who registered and closed the email has no
            other place they would find out. It is text with an icon-free, colour-free
            treatment on purpose: it is a note, not an alarm, and §9.2 forbids colour alone
            from carrying it in any case.
          */}
          {!session.emailVerified && (
            <p className="mx-1 mb-2 rounded-sm bg-surface-3 px-3 py-2 text-xs leading-relaxed text-white/64">
              Your email address is not verified yet. Open the link we sent to{' '}
              {session.email}.
            </p>
          )}

          <ul className="list-none">
            <li>
              <Link href="/notifications" className={ROW}>
                Notifications
              </Link>
            </li>
            <li>
              <Link href="/settings/notifications" className={ROW}>
                Notification settings
              </Link>
            </li>
            <li>
              <Link href="/settings/sessions" className={ROW}>
                Devices and sessions
              </Link>
            </li>
            <li>
              <Link href="/projects/new" className={ROW}>
                Start a campaign
              </Link>
            </li>
          </ul>

          <div className="mt-2 border-t border-white/6 pt-2">
            <button
              type="button"
              onClick={() => {
                setOpen(false);
                void onSignOut();
              }}
              className={cn(ROW, 'w-full text-left')}
            >
              Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
