import { describe, expect, it } from 'vitest';

import {
  DISCOVERY_SEGMENT_ID,
  MAX_URLS_PER_SITEMAP,
  PAGES_SEGMENT_ID,
  parseSitemapSegment,
  projectSegmentCount,
  projectSegmentId,
  projectSlice,
  sitemapSegmentIds,
} from './segments';

/**
 * Sharding. A sitemap holds at most 50,000 URLs and 50 MB uncompressed, and a
 * file that breaches either is rejected whole — so the limit is enforced here
 * rather than hoped for.
 */

describe('MAX_URLS_PER_SITEMAP', () => {
  it('leaves headroom under the 50,000 limit', () => {
    expect(MAX_URLS_PER_SITEMAP).toBeLessThan(50_000);
    expect(MAX_URLS_PER_SITEMAP).toBeGreaterThan(10_000);
  });
});

describe('projectSegmentCount', () => {
  it('is one shard for an empty platform, so the segment always exists', () => {
    expect(projectSegmentCount(0)).toBe(1);
  });

  it('is one shard right up to the limit', () => {
    expect(projectSegmentCount(1)).toBe(1);
    expect(projectSegmentCount(MAX_URLS_PER_SITEMAP)).toBe(1);
  });

  it('adds a shard the moment the limit is passed', () => {
    expect(projectSegmentCount(MAX_URLS_PER_SITEMAP + 1)).toBe(2);
    expect(projectSegmentCount(MAX_URLS_PER_SITEMAP * 2)).toBe(2);
    expect(projectSegmentCount(MAX_URLS_PER_SITEMAP * 2 + 1)).toBe(3);
  });
});

describe('sitemapSegmentIds', () => {
  it('segments by content type, then shards the projects', () => {
    expect(sitemapSegmentIds(0)).toEqual(['pages', 'discovery', 'projects-0']);
  });

  it('names one project segment per shard, zero based', () => {
    expect(sitemapSegmentIds(MAX_URLS_PER_SITEMAP * 2 + 1)).toEqual([
      'pages',
      'discovery',
      'projects-0',
      'projects-1',
      'projects-2',
    ]);
  });

  it('keeps the content-type segments first so the index reads in that order', () => {
    const ids = sitemapSegmentIds(MAX_URLS_PER_SITEMAP + 1);
    expect(ids[0]).toBe(PAGES_SEGMENT_ID);
    expect(ids[1]).toBe(DISCOVERY_SEGMENT_ID);
  });
});

describe('projectSegmentId', () => {
  it('is the shard index behind a stable prefix', () => {
    expect(projectSegmentId(0)).toBe('projects-0');
    expect(projectSegmentId(7)).toBe('projects-7');
  });
});

describe('parseSitemapSegment', () => {
  it('reads each content-type segment', () => {
    expect(parseSitemapSegment('pages')).toEqual({ kind: 'pages' });
    expect(parseSitemapSegment('discovery')).toEqual({ kind: 'discovery' });
  });

  it('reads a project shard and its index', () => {
    expect(parseSitemapSegment('projects-0')).toEqual({ kind: 'projects', index: 0 });
    expect(parseSitemapSegment('projects-12')).toEqual({ kind: 'projects', index: 12 });
  });

  it('refuses anything else rather than serving an arbitrary segment', () => {
    expect(parseSitemapSegment(undefined)).toBeNull();
    expect(parseSitemapSegment('')).toBeNull();
    expect(parseSitemapSegment('projects')).toBeNull();
    expect(parseSitemapSegment('projects-')).toBeNull();
    expect(parseSitemapSegment('projects--1')).toBeNull();
    expect(parseSitemapSegment('projects-01')).toBeNull();
    expect(parseSitemapSegment('projects-1.5')).toBeNull();
    expect(parseSitemapSegment('projects-1e3')).toBeNull();
    expect(parseSitemapSegment('PAGES')).toBeNull();
  });
});

describe('projectSlice', () => {
  it('cuts the shard the segment asked for', () => {
    const items = Array.from({ length: MAX_URLS_PER_SITEMAP + 3 }, (_, index) => index);

    expect(projectSlice(items, 0)).toHaveLength(MAX_URLS_PER_SITEMAP);
    expect(projectSlice(items, 1)).toEqual([
      MAX_URLS_PER_SITEMAP,
      MAX_URLS_PER_SITEMAP + 1,
      MAX_URLS_PER_SITEMAP + 2,
    ]);
  });

  it('is empty for a shard beyond the end', () => {
    expect(projectSlice([1, 2, 3], 1)).toEqual([]);
  });
});
