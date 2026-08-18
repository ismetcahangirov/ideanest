/**
 * Whether an image address may go through the optimiser.
 *
 * THE PROBLEM THIS SOLVES IS A CRASH, not a preference. `next/image` throws
 * when it is handed a URL no `remotePatterns` entry matches, and a thrown
 * render in a server component takes the whole route with it. Covers are
 * addresses creators type in by hand today — there is no uploader and no object
 * storage yet (docs/architecture.md §13.1) — so one creator pasting `http://`
 * or a typo would blank the discovery feed for everybody.
 *
 * So the decision is made here, before the element is chosen: an address that
 * can be optimised is rendered optimised, and anything else is rendered
 * `unoptimized` inside the same reserved frame. The picture is never modern
 * format on that path, and it is never a broken page either.
 *
 * WHY HTTPS ONLY. `next.config.mjs` allows `https` hosts and nothing else. A
 * mixed-content `http://` image is blocked by the browser on an HTTPS page
 * regardless of what the optimiser would have done with it, and `data:` and
 * relative addresses have nothing for a remote optimiser to fetch.
 */

/**
 * A statically imported image needs none of this. `import cover from './x.jpg'`
 * hands `next/image` a `StaticImageData` whose width, height, and `blurDataURL`
 * were produced at build time from the file itself, so it is always optimisable
 * and always has a real placeholder. There is no such import in the application
 * today, which is why there is no code here for one: the path exists in
 * `next/image` and is documented in docs/architecture.md §13.1 rather than
 * wrapped in a helper nothing calls.
 */

/**
 * True when `next/image` may fetch and re-encode this address.
 *
 * Deliberately narrower than `remotePatterns`: the config decides which hosts
 * the optimiser is willing to talk to, and this decides whether the address is
 * even the kind of thing it could talk to at all. Both have to agree, and the
 * config is the one that is enforced.
 */
export function canOptimise(src: string): boolean {
  let parsed: URL;
  try {
    parsed = new URL(src);
  } catch {
    // A relative path is served by this application and needs no optimiser
    // permission — but it is also not something a creator can produce today,
    // so treating it as unoptimisable costs nothing and keeps the rule one line.
    return false;
  }

  return parsed.protocol === 'https:';
}

/**
 * The intrinsic size a cover was recorded with, or `null` when it is unusable.
 *
 * `0` is what a failed measurement leaves behind
 * (`lib/projects/coverImage.ts`), and `MediaFrame` would fall back to a 16:9
 * crop for it. Saying so explicitly is what lets a call site choose the crop
 * token instead, which is the honest answer when the real shape is unknown.
 */
export function intrinsicSize(size: {
  width: number;
  height: number;
}): { width: number; height: number } | null {
  const usable =
    Number.isFinite(size.width) &&
    Number.isFinite(size.height) &&
    size.width > 0 &&
    size.height > 0;

  return usable ? { width: size.width, height: size.height } : null;
}
