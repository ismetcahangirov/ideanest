import { describe, expect, it } from 'vitest';

import {
  API_ORIGIN_VARIABLE,
  SITE_URL_VARIABLE,
  absoluteUrl,
  apiOrigin,
  siteUrl,
} from './config';

/**
 * The base URL is configuration. A literal in source is a sitemap that is wrong
 * in every environment but one, and wrong silently — a crawler reads
 * `<loc>https://ideanest.az/...</loc>` on a staging host and takes it at face
 * value.
 */

describe('siteUrl', () => {
  it('comes from the environment', () => {
    expect(siteUrl({ [SITE_URL_VARIABLE]: 'https://ideanest.az' })).toBe('https://ideanest.az');
    expect(siteUrl({ [SITE_URL_VARIABLE]: 'https://staging.ideanest.az' })).toBe(
      'https://staging.ideanest.az',
    );
  });

  it('drops a trailing slash so that joining a path never doubles it', () => {
    expect(siteUrl({ [SITE_URL_VARIABLE]: 'https://ideanest.az/' })).toBe('https://ideanest.az');
  });

  it('falls back to the development origin when nothing is configured', () => {
    expect(siteUrl({})).toBe('http://localhost:3000');
  });

  it('refuses a value that is set but unusable rather than guessing', () => {
    expect(() => siteUrl({ [SITE_URL_VARIABLE]: 'ideanest.az' })).toThrow(/IDEANEST_SITE_URL/);
    expect(() => siteUrl({ [SITE_URL_VARIABLE]: 'ftp://ideanest.az' })).toThrow(
      /IDEANEST_SITE_URL/,
    );
  });

  it('treats an empty value as unset', () => {
    expect(siteUrl({ [SITE_URL_VARIABLE]: '   ' })).toBe('http://localhost:3000');
  });
});

describe('apiOrigin', () => {
  it('is the same variable next.config.mjs proxies to', () => {
    expect(API_ORIGIN_VARIABLE).toBe('IDEANEST_API_ORIGIN');
    expect(apiOrigin({ [API_ORIGIN_VARIABLE]: 'http://api.internal:8080/' })).toBe(
      'http://api.internal:8080',
    );
    expect(apiOrigin({})).toBe('http://localhost:8080');
  });
});

describe('absoluteUrl', () => {
  it('produces an absolute URL, which is the only kind a sitemap may carry', () => {
    expect(absoluteUrl('/discover', 'https://ideanest.az')).toBe('https://ideanest.az/discover');
    expect(absoluteUrl('/', 'https://ideanest.az')).toBe('https://ideanest.az/');
  });

  it('does not care whether the path was written with a leading slash', () => {
    expect(absoluteUrl('discover', 'https://ideanest.az')).toBe('https://ideanest.az/discover');
  });
});
