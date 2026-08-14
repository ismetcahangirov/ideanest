import '@testing-library/jest-dom/vitest';

/**
 * Browser APIs jsdom does not implement, stubbed so every component and every
 * story can render under vitest.
 *
 * Each stub says what needs it. A bare stub with no explanation is the kind of
 * thing that gets deleted a year later by somebody who cannot tell whether it
 * still matters.
 */

/* -------------------------------------------------------------------------
 * window.matchMedia
 *
 * Needed by: `useReducedMotion()` from `motion/react`, which every animating
 * component calls (`FadeUp`, `StaggerGroup`, `CountUp`, `FlipButton`, and the
 * overlay transitions). Without it, motion throws on first render.
 *
 * The stub answers `prefers-reduced-motion: no-preference` by default, so
 * snapshots capture the animated markup rather than the reduced fallback.
 * `setPrefersReducedMotion()` flips it and notifies listeners, which is how
 * the reduced-motion snapshot pass in `visual-regression.test.tsx` works —
 * motion reads the preference once and then relies on the `change` event, so
 * mutating `matches` silently would not be observed.
 * ---------------------------------------------------------------------- */

let prefersReducedMotion = false;

const mediaQueryLists = new Set<FakeMediaQueryList>();

/** Only the reduced-motion family varies; anything else reports no match. */
function evaluate(query: string): boolean {
  if (!query.includes('prefers-reduced-motion')) return false;
  // `(prefers-reduced-motion)` and `(prefers-reduced-motion: reduce)` both ask
  // "is motion reduced?"; only the explicit `no-preference` form inverts.
  return query.includes('no-preference') ? !prefersReducedMotion : prefersReducedMotion;
}

class FakeMediaQueryList extends EventTarget {
  readonly media: string;
  matches: boolean;
  onchange: ((this: MediaQueryList, ev: MediaQueryListEvent) => unknown) | null = null;

  constructor(media: string) {
    super();
    this.media = media;
    this.matches = evaluate(media);
  }

  /** Deprecated Safari-era API. Some libraries still reach for it first. */
  addListener(listener: EventListener): void {
    this.addEventListener('change', listener);
  }

  removeListener(listener: EventListener): void {
    this.removeEventListener('change', listener);
  }
}

/**
 * Set the simulated `prefers-reduced-motion` preference and notify every
 * media query list handed out so far.
 */
export function setPrefersReducedMotion(reduced: boolean): void {
  prefersReducedMotion = reduced;

  for (const mql of mediaQueryLists) {
    mql.matches = evaluate(mql.media);

    const event = new Event('change');
    // Listeners that read the event rather than the list still get the truth.
    Object.assign(event, { matches: mql.matches, media: mql.media });

    mql.onchange?.call(mql as unknown as MediaQueryList, event as MediaQueryListEvent);
    mql.dispatchEvent(event);
  }
}

window.matchMedia = (query: string): MediaQueryList => {
  const mql = new FakeMediaQueryList(query);
  mediaQueryLists.add(mql);
  return mql as unknown as MediaQueryList;
};

/* -------------------------------------------------------------------------
 * IntersectionObserver
 *
 * Needed by: `FadeUp` / `StaggerGroup` (`whileInView`) and `CountUp`
 * (`useInView`). jsdom has no layout engine, so there is no honest answer to
 * "is this on screen" — the stub records the observation and never reports an
 * intersection.
 *
 * That is the deterministic choice, and it is deliberate. Reporting an
 * intersection would start real animations: `CountUp` would tick a formatted
 * number for 800ms and `FadeUp` would interpolate a transform, so the markup
 * would depend on how fast the machine ran. Never intersecting pins every
 * animated component to its pre-entry state.
 * ---------------------------------------------------------------------- */

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

/* -------------------------------------------------------------------------
 * ResizeObserver
 *
 * Needed by: motion's layout measurement paths, which several components pull
 * in transitively through `motion/react`. jsdom never resizes anything, so a
 * no-op that never fires is both sufficient and deterministic.
 * ---------------------------------------------------------------------- */

class NoopResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

globalThis.ResizeObserver = NoopResizeObserver as unknown as typeof ResizeObserver;

/* -------------------------------------------------------------------------
 * scrollTo / scrollIntoView
 *
 * Needed by: `CardRail`'s scroll buttons and any focus management that scrolls
 * the focused element into view. jsdom defines neither and logs a loud "not
 * implemented" error for `window.scrollTo`, which buries real failures.
 * ---------------------------------------------------------------------- */

window.scrollTo = (): void => {};
Element.prototype.scrollTo = (): void => {};
Element.prototype.scrollIntoView = (): void => {};
