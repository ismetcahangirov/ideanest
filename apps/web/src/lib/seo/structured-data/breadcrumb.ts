import type { Locale } from '../../i18n/locale';
import { canonicalUrl, sitePath, type EnvSource } from '../metadata';
import { localePath } from '../sitemap/localised';
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
 * WHAT THE FIXED STEPS ARE CALLED, IN THE LANGUAGE OF THE PAGE THEY ARE ON — #123.
 *
 * These were four frozen constants with English names in them, which was correct while the
 * application had one language. It has four, and a `BreadcrumbList` reading `Home → Discover`
 * on `/ru/discover` is a machine-readable claim contradicting the words a reader can see two
 * inches above it. Google's own guidance is that the markup must describe the page as it is
 * presented; four crumbs it never localised are four sentences the page does not say.
 *
 * <p>The words arrive from the catalogue through `lib/i18n/trail-copy.ts`, resolved on the
 * server by the route, exactly the way every other piece of localised copy reaches a component
 * in this application — not read here, because this module is the bottom of the SEO layer and
 * a `getTranslations` call at the bottom of it would make eight pure functions async.
 */
export interface TrailCopy {
  readonly home: string;
  readonly discover: string;
  readonly categories: string;
  readonly collections: string;
}

/**
 * The site itself.
 *
 * It was named here before the route existed, on the argument that this file states the
 * platform's public URL contract rather than an inventory of the routes that happen to be
 * built. #264 built it, so the crumb points at a page rather than at a promise.
 */
export function homeCrumb(copy: TrailCopy): Crumb {
  return { name: copy.home, path: '/' };
}

/** The feed. The one discovery URL that is indexable — see `DISCOVERY_PATHS`. */
export function discoverCrumb(copy: TrailCopy): Crumb {
  return { name: copy.discover, path: '/discover' };
}

/**
 * The taxonomy's index — §4.13 WS-05, issue #265.
 *
 * The intermediate step the module comment above was waiting for. It could not exist while
 * the only address for a category was `/discover?category=…`, which robots.txt disallows;
 * `/categories/games` is a page, so a trail through it is a claim that is true.
 */
export function categoriesCrumb(copy: TrailCopy): Crumb {
  return { name: copy.categories, path: '/categories' };
}

/**
 * Curation's index — D-08, §4.13 WS-04, issue #266.
 *
 * `categoriesCrumb`'s argument, one vocabulary over: an open call's only address used to be
 * `/discover?programme=…`, which robots.txt disallows wholesale, so a crumb naming a
 * collection had nowhere true to point. `/collections/spring-2027` is a page, so the trail
 * through `/collections` is a claim that holds.
 *
 * The path is duplicated from `lib/collections/api.ts`'s `COLLECTIONS_PATH` rather than
 * imported, exactly as the three above are literals rather than imports from their own feature
 * modules. This module is the bottom of the SEO layer and reaching up into a feature to
 * build a trail would invert that — and a route's own module is not where "what does a
 * crawler call this step" is decided.
 */
export function collectionsCrumb(copy: TrailCopy): Crumb {
  return { name: copy.collections, path: '/collections' };
}

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
  locale: Locale,
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
       *
       * `localePath` in the middle, since #123. Without it every step named
       * the un-prefixed address, which `proxy.ts` answers with a 307 — so a
       * trail whose whole purpose is to name the pages a reader came through
       * would have named four redirects, and the last step would have
       * contradicted the canonical of the page it was emitted on.
       *
       * The order matters and `sitePath` is why it is safe: the path is reduced
       * to our own origin BEFORE the language is prefixed, so a crumb whose
       * path arrived as `https://elsewhere.example/steal` becomes `/en/steal`
       * rather than `/en` glued to somebody else's URL.
       */
      item: canonicalUrl(localePath(sitePath(crumb.path, env), locale), env),
    })),
  };
}
