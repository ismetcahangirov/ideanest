/**
 * Anchored positioning for `Popover` and `Tooltip`. See docs/ui-kit.md §7.14.
 *
 * Deliberately not a positioning library. The product needs four sides and one
 * flip; a full middleware pipeline is thirty times the code for behaviour
 * nobody asked for. Keeping the arithmetic here as a pure function is also the
 * only way to test it — jsdom has no layout engine, so a rendered popover
 * measures zero by zero and asserts nothing.
 */

export type Placement = 'bottom' | 'top' | 'left' | 'right';

export interface Rect {
  top: number;
  left: number;
  width: number;
  height: number;
}

export interface Viewport {
  width: number;
  height: number;
}

export interface Position {
  top: number;
  left: number;
  /** The side actually used, which may differ from the one requested. */
  placement: Placement;
}

const OPPOSITE: Record<Placement, Placement> = {
  top: 'bottom',
  bottom: 'top',
  left: 'right',
  right: 'left',
};

/** Distance between the anchor and the panel. */
export const ANCHOR_GAP = 8;

/** Smallest allowed distance from the viewport edge. */
export const VIEWPORT_MARGIN = 8;

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, max));
}

function fits(
  placement: Placement,
  anchor: Rect,
  panel: Rect,
  viewport: Viewport,
  gap: number,
): boolean {
  switch (placement) {
    case 'top':
      return anchor.top - gap - panel.height >= 0;
    case 'bottom':
      return anchor.top + anchor.height + gap + panel.height <= viewport.height;
    case 'left':
      return anchor.left - gap - panel.width >= 0;
    case 'right':
      return anchor.left + anchor.width + gap + panel.width <= viewport.width;
  }
}

export interface ResolvePlacementOptions {
  gap?: number;
  margin?: number;
}

/**
 * Places `panel` beside `anchor` in viewport coordinates.
 *
 * Flips to the opposite side when the requested one does not fit and the
 * opposite one does. If neither fits the request is honoured and the result is
 * clamped, because a panel pinned to the wrong edge is more confusing than one
 * that overflows slightly in the direction the user expected.
 */
export function resolvePlacement(
  anchor: Rect,
  panel: Rect,
  viewport: Viewport,
  preferred: Placement = 'bottom',
  { gap = ANCHOR_GAP, margin = VIEWPORT_MARGIN }: ResolvePlacementOptions = {},
): Position {
  const opposite = OPPOSITE[preferred];
  const placement =
    fits(preferred, anchor, panel, viewport, gap) || !fits(opposite, anchor, panel, viewport, gap)
      ? preferred
      : opposite;

  let top: number;
  let left: number;

  switch (placement) {
    case 'top':
      top = anchor.top - gap - panel.height;
      left = anchor.left + anchor.width / 2 - panel.width / 2;
      break;
    case 'bottom':
      top = anchor.top + anchor.height + gap;
      left = anchor.left + anchor.width / 2 - panel.width / 2;
      break;
    case 'left':
      top = anchor.top + anchor.height / 2 - panel.height / 2;
      left = anchor.left - gap - panel.width;
      break;
    case 'right':
      top = anchor.top + anchor.height / 2 - panel.height / 2;
      left = anchor.left + anchor.width + gap;
      break;
  }

  return {
    top: clamp(top, margin, viewport.height - panel.height - margin),
    left: clamp(left, margin, viewport.width - panel.width - margin),
    placement,
  };
}

/** `getBoundingClientRect` reduced to the four numbers the maths needs. */
export function rectOf(element: Element): Rect {
  const { top, left, width, height } = element.getBoundingClientRect();
  return { top, left, width, height };
}
