import { canonicalUrl, type EnvSource } from '../metadata';
import type { JsonLdNode } from './document';

/**
 * The trail that reaches a page.
 *
 * A `BreadcrumbList` is a claim about how a visitor GOT here, so every step has
 * to be a page that exists and a link that would have been followed. That rules
 * out the two shapes this markup is usually written in: a trail invented from
 * the URL segments (`/projects/ayan/studio` is not three pages), and a trail
 * that names a category the campaign happens to be in without knowing which one it is. #265
 * built the path-based category routes this comment used to say did not exist, so a trail
 * through one is now possible — `categoryPageGraph` uses it. The campaign page's trail still
 * does not, because the campaign projection does not carry the campaign's taxonomy and a
 * crumb guessed from anything else would be a claim about a page nobody linked from.
 *
 * So the trail is short and true: the site, the feed, and the page itself.
 *
 * EVERY STEP CARRIES ITS `item`, INCLUDING THE LAST. Google says the final crumb
 * does not need one because it is the current page; "does not need" is not "must
 * not", and a `ListItem` with a position and a name and no identity is harder to
 * read in a validator than one that says plainly which URL it means.
 */

/** One step of the trail: what it is called, and the path it points at. */
export interface Crumb {
  readonly name: string;
  /** A route path. Any query string on it is dropped — see `canonicalUrl`. */
  readonly path: string;
}

/**
 * The site itself.
 *
 * It was named here before the route existed, on the argument that this file states the
 * platform's public URL contract rather than an inventory of the routes that happen to be
 * built. #264 built it, so the crumb now points at a page rather than at a promise.
 */
export const HOME_CRUMB: Crumb = Object.freeze({ name: 'Home', path: '/' });

/** The feed. The one discovery URL that is indexable — see `DISCOVERY_PATHS`. */
export const DISCOVER_CRUMB: Crumb = Object.freeze({ name: 'Discover', path: '/discover' });

/**
 * The taxonomy's index — §4.13 WS-05, issue #265.
 *
 * The intermediate step the module comment above was waiting for. It could not exist while
 * the only address for a category was `/discover?category=…`, which robots.txt disallows;
 * `/categories/games` is a page, so a trail through it is a claim that is true.
 */
export const CATEGORIES_CRUMB: Crumb = Object.freeze({ name: 'Categories', path: '/categories' });

/**
 * Whitespace collapsed and trimmed.
 *
 * A campaign title arrives with the newlines a textarea put in it, and a newline
 * inside a JSON string is legal, survives serialisation, and reads as a broken
 * name in every validator that prints the trail back. `lib/seo/metadata.ts` does
 * the same thing to a description for the same reason.
 */
function collapsed(text: string): string {
  return text.replace(/\s+/gu, ' ').trim();
}

/**
 * The trail, or `null` when there is not one.
 *
 * FEWER THAN TWO STEPS IS NOT A TRAIL. A single-item `BreadcrumbList` says "you
 * got here from here", which is not navigation, and emitting one on every page
 * that happens to have no parent is how a graph fills up with nodes that assert
 * nothing. A crumb with no name is dropped rather than numbered, and the
 * positions are assigned afterwards, so a dropped step never leaves a hole in
 * the sequence — a `BreadcrumbList` numbered 1, 3 is invalid, not merely odd.
 */
export function breadcrumbNode(
  trail: readonly Crumb[],
  env: EnvSource = process.env,
): JsonLdNode | null {
  const steps = trail
    .map((crumb) => ({ name: collapsed(crumb.name), path: crumb.path }))
    .filter((crumb) => crumb.name !== '');

  if (steps.length < 2) return null;

  return {
    '@type': 'BreadcrumbList',
    itemListElement: steps.map((crumb, index) => ({
      '@type': 'ListItem',
      position: index + 1,
      name: crumb.name,
      /*
       * Composed through `canonicalUrl`, which rebuilds the path on our own
       * origin. A crumb whose path arrived from a request — a slug, a title —
       * therefore cannot point the trail at another host, and the URL in the
       * trail is character for character the one in the `<link rel=canonical>`.
       */
      item: canonicalUrl(crumb.path, env),
    })),
  };
}
