import '@testing-library/jest-dom/vitest';

/**
 * Browser APIs jsdom does not implement, stubbed so `@ideanest/ui` components
 * render under vitest. The package's own `src/test-setup.ts` explains each of
 * these at length; this is the subset the web application actually reaches.
 *
 * EVERY STUB IS GUARDED, because not every test file wants a browser. A file that
 * declares `@vitest-environment node` gets no `window` and no `Element` — the
 * Open Graph image tests need exactly that, since `next/og` rasterises through
 * WebAssembly that will not accept jsdom's typed arrays — and an unguarded
 * assignment here would fail those files during setup, before a single test ran.
 */
const isBrowserLike = typeof window !== 'undefined';

/**
 * `useReducedMotion()` from `motion/react` runs on every overlay render, and
 * throws without `matchMedia`. Answering "no preference" keeps the animated
 * path under test — the reduced path is the one the browser gives for free.
 */
if (isBrowserLike) {
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
}

/**
 * `FadeUp` triggers on `whileInView`, which motion implements with an
 * `IntersectionObserver`. jsdom has no layout engine, so there is no honest
 * answer to "is this on screen" — the stub records the observation and never
 * reports an intersection, which pins the animated component to its pre-entry
 * state instead of letting a real transform interpolate at whatever speed the
 * machine happens to run. The package's own `src/test-setup.ts` explains it at
 * length; this is the same stub for the same reason.
 */
class NeverIntersectingObserver {
  readonly root: Element | Document | null = null;
  readonly rootMargin: string = '0px';
  readonly thresholds: ReadonlyArray<number> = [0];

  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }
}
globalThis.IntersectionObserver =
  NeverIntersectingObserver as unknown as typeof IntersectionObserver;

/** motion measures layout through it; jsdom never resizes anything. */
class NoopResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
globalThis.ResizeObserver = NoopResizeObserver as unknown as typeof ResizeObserver;

/** jsdom logs a loud "not implemented" for these, which buries real failures. */
if (isBrowserLike) {
  window.scrollTo = (): void => {};
  Element.prototype.scrollTo = (): void => {};
  Element.prototype.scrollIntoView = (): void => {};
}
