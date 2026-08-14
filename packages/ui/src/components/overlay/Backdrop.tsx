import { motion } from 'motion/react';
import { cn } from '../../lib/cn';
import { useBackdropDismiss } from './useDismiss';
import { useOverlayEntry } from './overlayMotion';

/**
 * The scrim behind a modal or a drawer. Internal — not part of the barrel.
 *
 * It fades and nothing else (docs/motion-system.md §4.11): the content behind
 * it is what the user was reading, and sliding or scaling a scrim over it
 * draws the eye to the wrong layer.
 *
 * `aria-hidden` because it carries no information; dismissal is also on
 * Escape, so a keyboard user never needs to reach it.
 */
export interface BackdropProps {
  /** Whether a click on the backdrop dismisses the overlay. */
  dismissible: boolean;
  onDismiss: () => void;
  className?: string;
}

export function Backdrop({ dismissible, onDismiss, className }: BackdropProps) {
  const handlers = useBackdropDismiss(dismissible, onDismiss);
  const entry = useOverlayEntry({ opacity: 0 });

  return (
    <motion.div
      aria-hidden="true"
      data-overlay-backdrop=""
      className={cn('absolute inset-0 bg-black/64', className)}
      {...entry}
      {...handlers}
    />
  );
}
