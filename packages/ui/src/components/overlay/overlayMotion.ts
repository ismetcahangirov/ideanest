import { useReducedMotion, type HTMLMotionProps, type TargetAndTransition } from 'motion/react';

/**
 * Entry motion shared by every overlay. See docs/motion-system.md §4.11.
 *
 * Overlays are the surface of checkout — the reward sheet, the pledge
 * confirmation, the address drawer — and the budget there is near zero
 * (docs/motion-system.md §5). So:
 *
 *   - 200ms. Long enough to read as arrival, short enough that nobody waits.
 *   - `transform` and `opacity` only. A fixed full-height drawer animating
 *     `right` would relayout the page on every frame.
 *   - There is no exit animation. A dialog that lingers after it was dismissed
 *     reads as an unresponsive interface, and holding a backdrop on screen for
 *     another 200ms puts a dead zone over the page the user just returned to.
 */
export const OVERLAY_ENTRY_MS = 200;

export type OverlayEntryFrom = Pick<TargetAndTransition, 'opacity' | 'x' | 'y'>;

/**
 * Motion props for an overlay surface. Under `prefers-reduced-motion` it
 * returns nothing at all, so the element mounts already in its final state —
 * an instant state change, not a fast animation.
 */
export function useOverlayEntry(from: OverlayEntryFrom): HTMLMotionProps<'div'> {
  const reduced = useReducedMotion();
  if (reduced) return {};

  return {
    initial: from,
    animate: { opacity: 1, x: 0, y: 0 },
    transition: { duration: OVERLAY_ENTRY_MS / 1000, ease: [0.4, 0, 0.2, 1] as const },
  };
}
