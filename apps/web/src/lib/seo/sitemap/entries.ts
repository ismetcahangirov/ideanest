import type { MetadataRoute } from 'next';

import { isIndexableProjectState } from '../indexability';
import { absoluteUrl } from './config';
import type { SitemapProject } from './projects';

/**
 * Turning data into `<url>` entries.
 *
 * Pure, and deliberately so: everything decided here — which campaigns are
 * listed, what date is claimed for them, what is said about how often they
 * change — is a statement to a crawler, and each one is tested rather than
 * reviewed.
 */

type SitemapEntry = MetadataRoute.Sitemap[number];
type ChangeFrequency = NonNullable<SitemapEntry['changeFrequency']>;

/**
 * The static pages.
 *
 * `/` IS LISTED AND DOES NOT EXIST YET — `apps/web` has no root route, and the
 * server-rendered home and project pages are #119. This list is the platform's
 * public URL contract (§4.4, §10.2) rather than an inventory of the routes that
 * happen to be built, because a sitemap that has to be rewritten by whoever
 * ships the page is a sitemap that gets shipped without it. The gap is named in
 * `apps/web/README.md` and in the pull request rather than hidden.
 *
 * Nothing here claims a `lastModified`: these pages are code, their content
 * changes when this application is deployed, and this module has no honest way
 * to know when that was. An invented date — `new Date()`, say — tells a crawler
 * that every page changed on every crawl, which is the fastest way to have
 * `<lastmod>` ignored altogether.
 */
export const PAGE_PATHS: readonly string[] = Object.freeze(['/']);

/**
 * Discovery.
 *
 * ONE URL, AND THAT IS THE HONEST ANSWER TODAY. §4.3's taxonomy — fifteen
 * categories and about a hundred subcategories — would make excellent landing
 * pages, but the only URL that reaches one is `/discover?category=games`, and
 * the filters compose into a combinatorial set of query strings describing the
 * same campaigns. robots.txt disallows `/discover?` wholesale for that reason,
 * and a sitemap must never advertise a URL robots.txt blocks. A path-based
 * category route is what makes those pages indexable, and it belongs with the
 * server-rendered discovery pages in #119.
 */
export const DISCOVERY_PATHS: readonly string[] = Object.freeze(['/discover']);

/** §10.2's canonical public project URL. */
export function projectPath(creatorSlug: string, projectSlug: string): string {
  return `/projects/${encodeURIComponent(creatorSlug)}/${encodeURIComponent(projectSlug)}`;
}

function parsed(value: string | null | undefined): Date | null {
  if (value == null || value === '') return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * When the campaign's page last actually changed, as far as the public listing
 * can say.
 *
 * `GET /v1/discover` carries two timestamps and no `updatedAt`, so these two are
 * what there is:
 *
 *   - `launchedAt` — the campaign became a public page.
 *   - `deadline`, ONCE IT HAS PASSED — the page changed at that moment, from a
 *     campaign taking pledges to one that succeeded or did not. A deadline in
 *     the future is not a modification; it is a promise, and claiming it as
 *     `<lastmod>` would date every live campaign months ahead.
 *
 * ABSENT RATHER THAN INVENTED. A campaign with neither gets no `<lastmod>` at
 * all. `<lastmod>` is the one field a crawler uses to decide whether to spend a
 * request, and a field that always says "now" is a field that gets ignored — for
 * this campaign and for every other one in the file.
 */
export function lastModifiedOf(project: SitemapProject, now: Date): Date | undefined {
  const launched = parsed(project.launchedAt);
  const deadline = parsed(project.deadline);
  const passed = deadline !== null && deadline.getTime() <= now.getTime() ? deadline : null;

  const candidates = [launched, passed].filter((date): date is Date => date !== null);
  if (candidates.length === 0) return undefined;

  return new Date(Math.max(...candidates.map((date) => date.getTime())));
}

/**
 * How often the page changes. A statement about the content, and true.
 *
 * A live campaign's page carries an amount raised, a backer count and a
 * countdown, and all three move daily. A campaign in fulfilment posts updates
 * over weeks. A completed or unsuccessful one is finished — its page will not
 * change again, and `yearly` is as close as the vocabulary gets to saying so
 * without `never`, which sitemaps.org reserves for archived URLs.
 *
 * There is no `priority` anywhere in this file, and that is the considered
 * answer rather than an omission. `<priority>` asks for the relative importance
 * of a URL *within this site*, on a scale with no defined meaning; Google states
 * plainly that it ignores the field, and there is no number here that would be
 * true if it did not. A field invented to fill a column is a field a reviewer
 * has to pretend to check.
 */
function changeFrequencyFor(state: string): ChangeFrequency {
  switch (state) {
    case 'LIVE':
    case 'LATE_PLEDGE':
      return 'daily';
    case 'SUCCESSFUL':
    case 'COLLECTING':
    case 'FULFILLING':
      return 'monthly';
    default:
      return 'yearly';
  }
}

/**
 * The project entries for a set of campaigns.
 *
 * THE PREDICATE IS APPLIED HERE TOO. `fetchIndexableProjects` already filtered,
 * and the service filtered before that. This is the last gate before a URL is
 * written into a file a search engine reads, and it costs one call.
 */
export function projectEntries(
  projects: readonly SitemapProject[],
  baseUrl: string,
  now: Date,
): MetadataRoute.Sitemap {
  return projects
    .filter((project) => isIndexableProjectState(project.state))
    .map((project) => {
      const lastModified = lastModifiedOf(project, now);

      return {
        url: absoluteUrl(projectPath(project.creatorSlug, project.slug), baseUrl),
        // Written conditionally rather than as `undefined`, so that the object a
        // test compares is the object the XML is built from.
        ...(lastModified === undefined ? {} : { lastModified }),
        changeFrequency: changeFrequencyFor(project.state),
      };
    });
}

function pathEntries(paths: readonly string[], baseUrl: string): MetadataRoute.Sitemap {
  return paths.map((path) => ({
    url: absoluteUrl(path, baseUrl),
    changeFrequency: 'daily' as const,
  }));
}

/** The `pages` segment. */
export function pageEntries(baseUrl: string): MetadataRoute.Sitemap {
  return pathEntries(PAGE_PATHS, baseUrl);
}

/** The `discovery` segment. */
export function discoveryEntries(baseUrl: string): MetadataRoute.Sitemap {
  return pathEntries(DISCOVERY_PATHS, baseUrl);
}
