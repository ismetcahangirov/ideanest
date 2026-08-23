'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { ACCOUNT_GROUPS, isCurrentAccountLink } from '../../lib/account/navigation';

/**
 * The account area's own navigation — §4.2, issue #275.
 *
 * <h2>A client boundary, and only for `usePathname`</h2>
 *
 * Nothing here has state and nothing fetches. What it needs is the current path, to mark one
 * entry `aria-current="page"`, and that is not knowable in a layout on the server without
 * threading it down from every page. One small boundary beside the content is the cheaper of
 * the two — the alternative is every page in the area passing its own path to its layout,
 * which is a rule somebody eventually forgets and nothing catches.
 *
 * <h2>`aria-current` carries it; the treatment is the second signal</h2>
 *
 * docs/ui-kit.md §9.2 forbids colour alone. The current entry is white on `--surface-2` with
 * a lime rule down its leading edge, and it is the `aria-current` that a screen reader reads.
 * Neither is sufficient on its own and both are present.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 puts "authentication, account settings" at **none — 150ms colour
 * on controls**. A navigation that animated between entries would be animating on every
 * screen in the area, on a surface whose whole job is to be worked in.
 *
 * <h2>It scrolls sideways on a phone, rather than collapsing</h2>
 *
 * Below the breakpoint the two groups become one horizontally scrolling row. A drawer would
 * be a second off-canvas panel in an application that already has one (WS-03) and would hide
 * the eight destinations behind a control, on the screens where somebody arrived specifically
 * to reach one of them.
 */
export function AccountNav() {
  const pathname = usePathname();

  return (
    <nav aria-label="Account" className="lg:sticky lg:top-24">
      <ul className="flex list-none gap-x-6 gap-y-8 overflow-x-auto pb-2 lg:flex-col lg:overflow-visible lg:pb-0">
        {ACCOUNT_GROUPS.map((group) => (
          <li key={group.heading} className="min-w-max lg:min-w-0">
            <h2 className="px-3 text-xs font-medium tracking-[0.08em] text-white/40 uppercase">
              {group.heading}
            </h2>
            <ul className="mt-2 flex list-none gap-1 lg:flex-col">
              {group.links.map((link) => {
                const current = isCurrentAccountLink(link.href, pathname);
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
