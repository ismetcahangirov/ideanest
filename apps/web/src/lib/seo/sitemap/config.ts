/**
 * Where this application answers, and where the service does.
 *
 * A sitemap carries absolute URLs — the format requires it, and a crawler has no
 * way to resolve a relative one. So the origin has to come from somewhere, and
 * the one place it must not come from is a literal in source: the same build is
 * deployed to production and to staging, and a literal makes the staging
 * sitemap advertise production URLs, or the reverse. Either is a lie a crawler
 * believes.
 */

/** The public origin this application is reachable at. */
export const SITE_URL_VARIABLE = 'IDEANEST_SITE_URL';

/**
 * Where the Spring Boot service listens — the same variable `next.config.mjs`
 * proxies `/v1/*` to, so there is one answer to "where is the API" per
 * environment rather than two that can disagree.
 */
export const API_ORIGIN_VARIABLE = 'IDEANEST_API_ORIGIN';

/** Development defaults, and only development defaults. */
const DEFAULT_SITE_URL = 'http://localhost:3000';
const DEFAULT_API_ORIGIN = 'http://localhost:8080';

type Env = Record<string, string | undefined>;

/**
 * Read at call time and never at module load.
 *
 * `robots.ts` and `sitemap.ts` are both `force-dynamic`, so the value in force
 * is the deployed environment's rather than whatever was set on the machine that
 * ran the build.
 */
function configured(env: Env, variable: string): string | null {
  const raw = env[variable];
  if (raw === undefined) return null;
  const trimmed = raw.trim();
  return trimmed === '' ? null : trimmed;
}

/**
 * An origin, with no trailing slash.
 *
 * A value that is SET BUT UNUSABLE throws rather than falling back. An unset
 * variable is a developer running locally; a variable set to `ideanest.az` is a
 * misconfiguration, and quietly serving localhost URLs from production instead
 * would hide it until somebody read the sitemap by hand.
 */
function origin(env: Env, variable: string, fallback: string): string {
  const raw = configured(env, variable);
  if (raw === null) return fallback;

  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error(`${variable} is not an absolute URL: ${JSON.stringify(raw)}`);
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`${variable} must be http or https, not ${url.protocol}`);
  }

  return url.origin + url.pathname.replace(/\/+$/, '');
}

/** This application's public origin. */
export function siteUrl(env: Env = process.env): string {
  return origin(env, SITE_URL_VARIABLE, DEFAULT_SITE_URL);
}

/** The service's origin, for a server-side read that cannot go through the proxy. */
export function apiOrigin(env: Env = process.env): string {
  return origin(env, API_ORIGIN_VARIABLE, DEFAULT_API_ORIGIN);
}

/** An absolute URL for a path on this site. */
export function absoluteUrl(path: string, base: string = siteUrl()): string {
  return `${base}/${path.replace(/^\/+/, '')}`;
}
