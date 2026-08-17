import { describe, expect, it } from 'vitest';

import { SITEMAP_INDEX_PATH, segmentUrl, sitemapIndexXml } from './index-xml';

const BASE = 'https://ideanest.az';

/**
 * The index. Next serves one file per segment at `/sitemap/{id}.xml` and does
 * not write an index of them, so this is ours — and it is what robots.txt
 * points a crawler at.
 */

describe('segmentUrl', () => {
  it('is the URL Next serves a segment at', () => {
    expect(segmentUrl('pages', BASE)).toBe('https://ideanest.az/sitemap/pages.xml');
    expect(segmentUrl('projects-3', BASE)).toBe('https://ideanest.az/sitemap/projects-3.xml');
  });
});

describe('sitemapIndexXml', () => {
  const xml = sitemapIndexXml(['pages', 'discovery', 'projects-0', 'projects-1'], BASE);

  it('is a sitemap index, declared as one', () => {
    expect(xml.startsWith('<?xml version="1.0" encoding="UTF-8"?>')).toBe(true);
    expect(xml).toContain('<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">');
    expect(xml.trimEnd().endsWith('</sitemapindex>')).toBe(true);
  });

  it('references every segment it was given, in order', () => {
    expect(xml).toContain('<loc>https://ideanest.az/sitemap/pages.xml</loc>');
    expect(xml).toContain('<loc>https://ideanest.az/sitemap/discovery.xml</loc>');
    expect(xml).toContain('<loc>https://ideanest.az/sitemap/projects-0.xml</loc>');
    expect(xml).toContain('<loc>https://ideanest.az/sitemap/projects-1.xml</loc>');

    expect(xml.indexOf('projects-0')).toBeLessThan(xml.indexOf('projects-1'));
    expect((xml.match(/<sitemap>/g) ?? []).length).toBe(4);
  });

  it('claims no lastmod, because the index itself has no date to report', () => {
    expect(xml).not.toContain('<lastmod>');
  });

  it('escapes a URL rather than emitting XML somebody else wrote', () => {
    expect(sitemapIndexXml(['a&b'], BASE)).toContain('/sitemap/a&amp;b.xml');
  });

  it('is served where robots.txt says it is', () => {
    expect(SITEMAP_INDEX_PATH).toBe('/sitemap_index.xml');
  });
});
