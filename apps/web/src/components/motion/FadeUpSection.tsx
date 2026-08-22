'use client';

import type { ReactNode } from 'react';
import { FadeUp } from '@ideanest/ui/motion';

/**
 * `FadeUp`, reachable from a Server Component.
 *
 * <h2>Why the indirection exists</h2>
 *
 * `@ideanest/ui`'s `FadeUp` drives `motion/react`, which uses hooks, so it can only be
 * rendered inside a client boundary. The home page and the category landing pages are Server
 * Components on purpose — their whole job is putting campaigns in the initial HTML — and
 * marking them `'use client'` to reach an entry animation would ship every one of those
 * campaigns to the browser to be re-rendered there.
 *
 * This is the one-line boundary that makes both true at once. **Its children are still
 * rendered on the server**: React resolves them before they are handed across as props, so a
 * card wrapped in this arrives as HTML and the only thing the browser is given is the
 * animation.
 *
 * <h2>Where it may be used</h2>
 *
 * docs/motion-system.md §5, and the answer is short: the home page, which is the one surface
 * with a **Full** budget, and section headings elsewhere. §5.1 forbids it inside a long list
 * outright — "fifty animated cards in a feed cost scroll performance" — so a caller wrapping
 * each item of a grid is using the wrong thing.
 *
 * Reduced motion is handled inside `FadeUp` itself; there is nothing to repeat here.
 */

export interface FadeUpSectionProps {
  readonly children: ReactNode;
  /** Milliseconds, as `FadeUp` takes it. Passed through unchanged. */
  readonly delay?: number;
  readonly className?: string;
}

export function FadeUpSection({ children, delay, className }: FadeUpSectionProps) {
  return (
    <FadeUp {...(delay === undefined ? {} : { delay })} {...(className === undefined ? {} : { className })}>
      {children}
    </FadeUp>
  );
}
