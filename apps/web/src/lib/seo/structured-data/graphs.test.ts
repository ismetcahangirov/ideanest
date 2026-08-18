import { describe, expect, it } from 'vitest';

import type { PublicProjectPreview } from '../metadata';
import type { JsonLdNode } from './document';
import type { PublicRewardTier } from './product';
import { discoverPageGraph, projectPageGraph } from './graphs';

const env = { IDEANEST_SITE_URL: 'https://ideanest.az' } as const;
const now = new Date('2026-08-18T09:00:00Z');
const path = '/projects/ayan/studio';

function types(nodes: readonly JsonLdNode[]): readonly (string | undefined)[] {
  return nodes.map((node) => node['@type'] as string | undefined);
}

function preview(overrides: Partial<PublicProjectPreview> = {}): PublicProjectPreview {
  return {
    id: 'ffa5a1e2-0000-7000-8000-000000000000',
    slug: 'studio',
    state: 'LIVE',
    title: 'A ceramics studio',
    blurb: 'Wheel-thrown tableware from a workshop in Quba.',
    coverImage: null,
    ...overrides,
  };
}

const tier: PublicRewardTier = {
  id: 'reward-1',
  title: 'The boxed set',
  description: null,
  price: { amount: '85.00', currency: 'AZN' },
  remainingQuantity: null,
  imageUrl: null,
};

describe('discoverPageGraph', () => {
  it('carries the site identity and the trail that reaches the feed', () => {
    expect(types(discoverPageGraph(env))).toEqual(['Organization', 'WebSite', 'BreadcrumbList']);
  });
});

describe('projectPageGraph', () => {
  function graph(overrides: Partial<Parameters<typeof projectPageGraph>[0]> = {}) {
    return projectPageGraph({
      preview: preview(),
      path,
      deadline: '2026-09-08T20:59:59Z',
      tiers: [tier],
      faqs: [{ question: 'When does it ship?', answer: 'March 2027.' }],
      env,
      now,
      ...overrides,
    });
  }

  it('states the trail, then every tier, then the questions', () => {
    expect(types(graph())).toEqual(['BreadcrumbList', 'Product', 'FAQPage']);
  });

  it('walks home, the feed, and the campaign itself', () => {
    const [breadcrumb] = graph();
    const items = (breadcrumb?.['itemListElement'] ?? []) as readonly { name: string }[];

    expect(items.map((item) => item.name)).toEqual(['Home', 'Discover', 'A ceramics studio']);
  });

  it('anchors every tier to the campaign the page is about', () => {
    const [, product] = graph();
    expect(product?.['@id']).toBe('https://ideanest.az/projects/ayan/studio#reward-reward-1');
  });

  it('says nothing at all about a campaign it could not confirm is public', () => {
    expect(graph({ preview: null })).toEqual([]);
    expect(graph({ preview: preview({ state: 'DRAFT' }) })).toEqual([]);
    expect(graph({ preview: preview({ state: 'SUBMITTED' }) })).toEqual([]);
  });

  it('still walks the trail for a campaign with no tiers and no questions', () => {
    expect(types(graph({ tiers: [], faqs: [] }))).toEqual(['BreadcrumbList']);
  });

  it('makes no offer on a campaign that is public but not taking pledges', () => {
    expect(types(graph({ preview: preview({ state: 'SUCCESSFUL' }) }))).toEqual([
      'BreadcrumbList',
      'FAQPage',
    ]);
  });
});
