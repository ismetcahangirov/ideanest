import { useCallback, useEffect, useRef } from 'react';

/**
 * Dismissal behaviour shared by every overlay. See docs/ui-kit.md §7.14.
 */

/**
 * Every open overlay, oldest first. Escape is handled by the LAST entry only,
 * so a popover opened inside a modal closes the popover and leaves the modal
 * standing. Without this, one keystroke tears down the whole stack and the
 * user loses work they had not finished.
 */
const overlayStack: object[] = [];

export interface DismissOptions {
  open: boolean;
  onDismiss: () => void;
  /** Opt out when the overlay is blocking on a decision the user must make. */
  closeOnEscape?: boolean;
}

export function useDismiss({ open, onDismiss, closeOnEscape = true }: DismissOptions): void {
  // A stable identity for this overlay instance; the value is never read.
  const token = useRef<object>({});

  useEffect(() => {
    if (!open) return;
    const entry = token.current;
    overlayStack.push(entry);
    return () => {
      const index = overlayStack.indexOf(entry);
      if (index !== -1) overlayStack.splice(index, 1);
    };
  }, [open]);

  useEffect(() => {
    if (!open || !closeOnEscape) return;

    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== 'Escape') return;
      if (overlayStack[overlayStack.length - 1] !== token.current) return;
      event.stopPropagation();
      onDismiss();
    }

    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, closeOnEscape, onDismiss]);
}

/* ── Scroll lock ────────────────────────────────────────────────────── */

let lockCount = 0;
let overflowBeforeLock = '';

/**
 * Freezes the page behind a modal or drawer.
 *
 * The original `overflow` is captured and put back verbatim. Resetting to `''`
 * instead would quietly delete an application's own scroll handling the first
 * time somebody opened a dialog, and nobody would connect the two.
 */
export function useScrollLock(active: boolean): void {
  useEffect(() => {
    if (!active) return;

    if (lockCount === 0) {
      overflowBeforeLock = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
    }
    lockCount += 1;

    return () => {
      lockCount -= 1;
      if (lockCount === 0) document.body.style.overflow = overflowBeforeLock;
    };
  }, [active]);
}

/* ── Backdrop click ─────────────────────────────────────────────────── */

export interface BackdropHandlers {
  onMouseDown: (event: { target: EventTarget | null; currentTarget: EventTarget | null }) => void;
  onClick: (event: { target: EventTarget | null; currentTarget: EventTarget | null }) => void;
}

/**
 * Closes on a click that both started and ended on the backdrop.
 *
 * Selecting text inside a dialog and releasing the mouse outside it produces a
 * click whose target is the backdrop. Treating that as "dismiss" throws away
 * whatever the user was doing, which is why the press origin is checked too.
 */
export function useBackdropDismiss(enabled: boolean, onDismiss: () => void): BackdropHandlers {
  const pressStartedOnBackdrop = useRef(false);

  const onMouseDown = useCallback<BackdropHandlers['onMouseDown']>((event) => {
    pressStartedOnBackdrop.current = event.target === event.currentTarget;
  }, []);

  const onClick = useCallback<BackdropHandlers['onClick']>(
    (event) => {
      const startedOutside = pressStartedOnBackdrop.current;
      pressStartedOnBackdrop.current = false;
      if (!enabled || !startedOutside) return;
      if (event.target !== event.currentTarget) return;
      onDismiss();
    },
    [enabled, onDismiss],
  );

  return { onMouseDown, onClick };
}
