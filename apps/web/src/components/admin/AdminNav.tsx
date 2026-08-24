'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { CONSOLE_GROUPS, isCurrentConsoleLink } from '../../lib/admin/navigation';

/**
 * The console's own navigation — §4.11, issue #294.
 *
 * <h2>A client boundary, and only for `usePathname`</h2>
 *
 * Nothing here has state and nothing fetches. What it needs is the current path, to mark one
 * entry `aria-current="page"`, and that is not knowable in a layout on the server without
 * every page threading its own path down — a rule somebody eventually forgets and nothing
 * catches. `AccountNav` draws the same boundary for the same reason.
 *
 * <h2>The entries are read from the module list rather than passed in</h2>
 *
 * Unlike `AccountNav`, which takes its words as props because they come out of the message
 * catalogue on the server. There is no catalogue here — `lib/admin/navigation.ts` says why
 * the console is English — so importing the list costs the browser the same handful of
 * strings the props would have, and having one source means the rail and the console index
 * cannot disagree about what exists.
 *
 * <h2>`aria-current` carries it; the treatment is the second signal</h2>
 *
 * docs/ui-kit.md §9.2 forbids colour alone. The current entry is white on `--surface-2` with
 * a lime rule down its leading edge, and it is the `aria-current` a screen reader reads.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 gives an administrative surface the same budget as account
 * settings — none, beyond 150ms of colour on a control. A console is worked in, and a rail
 * that animated would animate on every screen in it.
 */
export function AdminNav() {
  const pathname = usePathname();

  return (
    <nav aria-label="Administration console" className="lg:sticky lg:top-8">
      <ul className="flex list-none gap-x-6 gap-y-8 overflow-x-auto pb-2 lg:flex-col lg:overflow-visible lg:pb-0">
        {CONSOLE_GROUPS.map((group) => (
          <li key={group.heading} className="min-w-max lg:min-w-0">
            <h2 className="px-3 text-xs font-medium tracking-[0.08em] text-white/40 uppercase">
              {group.heading}
            </h2>
            <ul className="mt-2 flex list-none gap-1 lg:flex-col">
              {group.links.map((link) => {
                const current = isCurrentConsoleLink(link.href, pathname);
                return (
                  <li key={link.href}>
                    <Link
                      href={link.href}
                      aria-current={current ? 'page' : undefined}
                      className={[
                        'block rounded-lg border-l-2 px-3 py-2 text-[15px] transition-colors duration-150 ease-in-out',
                        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]',
                        current
                          ? 'border-[var(--lime-500)] bg-surface-2 text-white'
                          : 'border-transparent text-white/64 hover:bg-surface-2 hover:text-white',
                      ].join(' ')}
                    >
                      {link.label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </li>
        ))}
      </ul>
    </nav>
  );
}
