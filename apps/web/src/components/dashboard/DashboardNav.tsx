'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { cn } from '@ideanest/ui/server';

/**
 * The way between the dashboard's panels.
 *
 * <h2>It lists one item, and that is not a placeholder</h2>
 *
 * §4.7 describes nineteen capabilities and this shell has the first. The other panels are
 * #96, #97 and #99, and each adds its entry here when it adds its route. A navigation
 * that offered them now — disabled, or pointing at a 404 — would tell a creator the
 * dashboard is unfinished, which is the one thing a shell exists to avoid saying.
 *
 * <h2>A client component for one reason</h2>
 *
 * `usePathname`, to mark the current panel. That is the whole of it: no fetch, no state,
 * no effect. When the second entry lands this is where "which one am I on" is already
 * answered.
 *
 * <h2>Accessibility</h2>
 *
 * `aria-current="page"` carries the selection, not the colour. docs/ui-kit.md §9.2:
 * colour alone never carries meaning, and "which page am I on" is meaning. The underline
 * is a second, visual signal for the same fact.
 */

interface Panel {
  readonly href: string;
  readonly label: string;
}

function panelsFor(projectId: string): readonly Panel[] {
  const base = `/projects/${encodeURIComponent(projectId)}/dashboard`;
  return [{ href: base, label: 'Overview' }];
}

export interface DashboardNavProps {
  readonly projectId: string;
}

export function DashboardNav({ projectId }: DashboardNavProps) {
  const pathname = usePathname();
  const panels = panelsFor(projectId);

  return (
    <nav aria-label="Dashboard sections" className="border-b border-white/8">
      <ul className="flex gap-1">
        {panels.map((panel) => {
          const current = pathname === panel.href;
          return (
            <li key={panel.href}>
              <Link
                href={panel.href}
                aria-current={current ? 'page' : undefined}
                className={cn(
                  // The focus ring is on every item and is never removed: ui-kit §9.3
                  // makes it a build error, and a keyboard user on a nav with no visible
                  // focus has no way to know where they are.
                  'inline-block rounded-t-[10px] px-4 py-3 text-sm font-medium',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[--lime-500]',
                  current
                    ? 'border-b-2 border-white text-white'
                    : 'border-b-2 border-transparent text-white/64 hover:text-white',
                )}
              >
                {panel.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
