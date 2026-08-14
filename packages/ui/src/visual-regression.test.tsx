/// <reference types="vite/client" />
import { composeStories, setProjectAnnotations } from '@storybook/react-vite';
import { cleanup, render } from '@testing-library/react';
import * as previewAnnotations from '../.storybook/preview';
import { setPrefersReducedMotion } from './test-setup';

/**
 * Visual regression by DOM snapshot.
 *
 * Every story in the library is rendered through Storybook's portable-story
 * API — the same project annotations a reviewer sees in Storybook — and its
 * markup is snapshotted. A diff means the rendered output changed.
 *
 * Why markup and not pixels. This library is developed on Windows and built on
 * Linux CI. Font rasterisation, subpixel rounding, and scrollbar metrics all
 * differ between the two, so pixel baselines taken on one machine never match
 * the other: the suite would be permanently red, or every intended change
 * would need a Docker round-trip to regenerate a baseline. Meanwhile every
 * visual property in this system is carried by a Tailwind class name, and
 * class names are byte-identical on both platforms. Snapshotting the markup
 * catches the regressions that actually occur — a variant losing its
 * `bg-lime-500`, a padding scale shifting, an element disappearing, an ARIA
 * attribute coming unwired — deterministically, in the CI that already exists,
 * in seconds.
 *
 * What it does not catch: a change that lives purely in `styles.css` or
 * `theme.css` with no markup change, such as retuning `--lime-500` or
 * redefining what `bg-surface-2` resolves to. Those still need a human in
 * Storybook. Pixel diffing remains available as a later, separate addition;
 * it is not a replacement for this suite, and this suite is not a substitute
 * for it.
 */

/* -------------------------------------------------------------------------
 * Determinism
 *
 * A flaky snapshot suite is worse than no suite, because people learn to
 * re-run it until it passes. Four sources of nondeterminism are handled:
 * generated ids, the clock, viewport-triggered animation, and portals.
 * ---------------------------------------------------------------------- */

/**
 * Frozen clock.
 *
 * `Timeline` projects markers against `now`, and any story that reaches for
 * `new Date()` would move its output every day. No story does today — every
 * date in `Layout.stories.tsx` is a literal — but the failure mode is a
 * snapshot that rots silently rather than one that fails loudly, so the clock
 * is pinned regardless. Only `Date` is faked: `requestAnimationFrame` and
 * timers stay real, because motion and React depend on them.
 */
const FROZEN_NOW = new Date('2026-08-14T12:00:00.000Z');

/**
 * React's `useId` produces values like `«r0»` whose counter is per React
 * runtime, so it depends on how many components rendered earlier in the
 * process. The form primitives use it heavily, and `Field` derives further
 * ids from it (`«r0»-hint`, `«r0»-error`).
 *
 * Rather than patching each attribute — `id`, `for`, `aria-describedby`,
 * `aria-labelledby`, `aria-controls` — the raw token is replaced wherever it
 * appears in the markup, numbered by order of first appearance. Every
 * reference to an id therefore normalises to the same placeholder as the id
 * itself, and the wiring between them stays visible in the snapshot.
 */
const REACT_GENERATED_ID = /«[^»\s"]*»|:r[0-9a-z]+:|_r_[0-9a-z]+_/g;

function normaliseGeneratedIds(markup: string): string {
  const stableFor = new Map<string, string>();

  return markup.replace(REACT_GENERATED_ID, (token) => {
    const existing = stableFor.get(token);
    if (existing !== undefined) return existing;

    const stable = `[id-${stableFor.size}]`;
    stableFor.set(token, stable);
    return stable;
  });
}

/** One tag per line, so a failure diff points at an element, not a wall. */
function readable(markup: string): string {
  return markup.replace(/></g, '>\n<');
}

/**
 * Overlay content renders through `createPortal` into `document.body`, outside
 * the container testing-library hands back. Anything portalled is appended to
 * the snapshot under a marker so it is not silently dropped.
 *
 * In practice this is almost always empty: every overlay story starts closed,
 * so what gets captured is the trigger. That is a real coverage gap and it is
 * named in the README rather than papered over. It is captured anyway so that
 * an open-by-default overlay story added later is covered the day it lands.
 */
function serialise(container: HTMLElement): string {
  const portalled = Array.from(document.body.children)
    .filter((el) => el !== container && !el.contains(container))
    .map((el) => el.outerHTML)
    .join('');

  const markup = portalled ? `${container.innerHTML}<!-- portal -->${portalled}` : container.innerHTML;

  return normaliseGeneratedIds(readable(markup));
}

/* -------------------------------------------------------------------------
 * Discovery
 * ---------------------------------------------------------------------- */

type StoryModule = Parameters<typeof composeStories>[0];

/**
 * Discovered, never registered by hand. A component is covered the moment
 * somebody writes its story — nobody has to remember to add it here.
 */
const storyModules = import.meta.glob<StoryModule>('./**/*.stories.tsx', { eager: true });

const storyFiles = Object.keys(storyModules).sort();

/**
 * A green suite that tests nothing is worse than a red one. If a refactor
 * moves, renames, or empties the story tree, the glob quietly returns fewer
 * files and every snapshot stops being checked. These floors are well under
 * the current counts (30 files, 98 stories) and exist only to make that
 * failure loud.
 */
const MIN_STORY_FILES = 25;
const MIN_STORIES = 80;

setProjectAnnotations([previewAnnotations]);

interface DiscoveredStory {
  file: string;
  name: string;
  Story: React.ComponentType;
  load: () => Promise<void>;
}

const stories: DiscoveredStory[] = storyFiles.flatMap((file) => {
  const composed = composeStories(storyModules[file] as StoryModule);

  return Object.entries(composed).map(([name, Story]) => ({
    file,
    name,
    Story: Story as unknown as React.ComponentType,
    load: (Story as unknown as { load: () => Promise<void> }).load,
  }));
});

describe('story discovery', () => {
  it('finds the story files', () => {
    expect(storyFiles.length).toBeGreaterThanOrEqual(MIN_STORY_FILES);
  });

  it('finds the stories inside them', () => {
    expect(stories.length).toBeGreaterThanOrEqual(MIN_STORIES);
  });
});

/* -------------------------------------------------------------------------
 * The snapshots
 * ---------------------------------------------------------------------- */

beforeAll(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(FROZEN_NOW);
});

afterAll(() => {
  vi.useRealTimers();
  setPrefersReducedMotion(false);
});

afterEach(() => {
  cleanup();
});

async function snapshot(story: DiscoveredStory): Promise<string> {
  await story.load();
  const { container } = render(<story.Story />);
  return serialise(container);
}

describe('rendered markup', () => {
  beforeAll(() => {
    setPrefersReducedMotion(false);
  });

  for (const story of stories) {
    it(`${story.file} — ${story.name}`, async () => {
      expect(await snapshot(story)).toMatchSnapshot();
    });
  }
});

/**
 * The same sweep with `prefers-reduced-motion: reduce`.
 *
 * `prefers-reduced-motion` is mandatory in this system, which means the
 * reduced fallback is production markup and deserves the same protection as
 * the animated path. Most stories render identically in both passes — the
 * cost is a second copy of those snapshots, which is the price of catching
 * the case where a component quietly stops honouring the preference.
 */
describe('rendered markup (prefers-reduced-motion: reduce)', () => {
  beforeAll(() => {
    setPrefersReducedMotion(true);
  });

  afterAll(() => {
    setPrefersReducedMotion(false);
  });

  for (const story of stories) {
    it(`${story.file} — ${story.name} [reduced motion]`, async () => {
      expect(await snapshot(story)).toMatchSnapshot();
    });
  }
});
