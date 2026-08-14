import { useEffect, type RefObject } from 'react';

/**
 * Focus management for overlays. See docs/ui-kit.md §7.14.
 *
 * Hand-rolled rather than pulled from a library: the whole behaviour is forty
 * lines, and a dependency here would own the one thing a keyboard user cannot
 * work around if it breaks.
 */

/**
 * Elements that take sequential focus. `[tabindex="-1"]` is excluded on
 * purpose — it is programmatically focusable but not part of the Tab order,
 * and the panel itself carries it.
 */
const TABBABLE_SELECTOR = [
  'a[href]',
  'area[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  'audio[controls]',
  'video[controls]',
  'details > summary:first-of-type',
  'iframe',
  '[contenteditable]:not([contenteditable="false"])',
  '[tabindex]:not([tabindex^="-"])',
].join(',');

/**
 * Tabbable descendants in document order.
 *
 * Visibility is deliberately NOT computed from layout: jsdom reports every
 * element as unrendered, so an `offsetParent` check would silently make the
 * trap untestable. `hidden` and `aria-hidden` cover the cases that matter.
 */
export function tabbableElements(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(TABBABLE_SELECTOR)).filter(
    (element) =>
      !element.hasAttribute('hidden') &&
      element.getAttribute('aria-hidden') !== 'true' &&
      !element.closest('[inert]'),
  );
}

export interface FocusTrapOptions {
  /**
   * Cycle Tab inside the container. True for a modal and a drawer, where the
   * page behind is unreachable; false for a popover, which is non-modal and
   * must let Tab continue into the page.
   */
  trap?: boolean;
}

/**
 * Moves focus into `ref` while `active`, and returns it to whatever was
 * focused before on deactivation.
 *
 * Returning focus is not a nicety: without it a keyboard user who closes a
 * dialog lands back at the top of the document and has to re-navigate to the
 * control they just used.
 */
export function useFocusTrap(
  active: boolean,
  ref: RefObject<HTMLElement | null>,
  { trap = true }: FocusTrapOptions = {},
): void {
  useEffect(() => {
    if (!active) return;
    const container = ref.current;
    if (!container) return;

    const restoreTo = document.activeElement instanceof HTMLElement ? document.activeElement : null;

    // The panel itself is focusable (tabIndex -1) so an overlay with no
    // controls still takes focus rather than leaving it behind on the page.
    const [firstOnOpen] = tabbableElements(container);
    (firstOnOpen ?? container).focus();

    function onKeyDown(event: KeyboardEvent) {
      if (!trap || event.key !== 'Tab' || !container) return;

      const items = tabbableElements(container);
      const first = items[0];
      const last = items[items.length - 1];

      if (!first || !last) {
        // Nothing to move to — keep focus on the panel rather than letting it
        // escape to the page behind, which is inert to the user's eyes.
        event.preventDefault();
        container.focus();
        return;
      }

      const current = document.activeElement;
      if (event.shiftKey) {
        if (current === first || current === container) {
          event.preventDefault();
          last.focus();
        }
      } else if (current === last) {
        event.preventDefault();
        first.focus();
      }
    }

    container.addEventListener('keydown', onKeyDown);
    return () => {
      container.removeEventListener('keydown', onKeyDown);
      restoreTo?.focus();
    };
  }, [active, ref, trap]);
}
