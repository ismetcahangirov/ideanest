import '@testing-library/jest-dom/vitest';

/**
 * Browser APIs jsdom does not implement, stubbed so `@ideanest/ui` components
 * render under vitest. The package's own `src/test-setup.ts` explains each of
 * these at length; this is the subset the web application actually reaches.
 */

/**
 * `useReducedMotion()` from `motion/react` runs on every overlay render, and
 * throws without `matchMedia`. Answering "no preference" keeps the animated
 * path under test — the reduced path is the one the browser gives for free.
 */
window.matchMedia = (query: string): MediaQueryList =>
  ({
    media: query,
    matches: false,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }) as unknown as MediaQueryList;

/** motion measures layout through it; jsdom never resizes anything. */
class NoopResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
globalThis.ResizeObserver = NoopResizeObserver as unknown as typeof ResizeObserver;

/** jsdom logs a loud "not implemented" for these, which buries real failures. */
window.scrollTo = (): void => {};
Element.prototype.scrollTo = (): void => {};
Element.prototype.scrollIntoView = (): void => {};
