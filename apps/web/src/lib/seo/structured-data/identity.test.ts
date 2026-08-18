import { describe, expect, it } from 'vitest';

import { SITE_LANGUAGE, SITE_NAME } from '../metadata';
import { ORGANIZATION_FRAGMENT, WEBSITE_FRAGMENT, siteIdentityNodes } from './identity';

const env = { IDEANEST_SITE_URL: 'https://ideanest.az' } as const;

describe('siteIdentityNodes', () => {
  it('states the organisation and the site, once each', () => {
    const types = siteIdentityNodes(env).map((node) => node['@type']);
    expect(types).toEqual(['Organization', 'WebSite']);
  });

  it('identifies both against the configured origin, never a literal', () => {
    const [organization, website] = siteIdentityNodes(env);

    expect(organization?.['@id']).toBe(`https://ideanest.az/${ORGANIZATION_FRAGMENT}`);
    expect(organization?.url).toBe('https://ideanest.az/');
    expect(website?.['@id']).toBe(`https://ideanest.az/${WEBSITE_FRAGMENT}`);
    expect(website?.url).toBe('https://ideanest.az/');
  });

  it('follows the origin wherever it is configured, so staging never claims production', () => {
    const [organization] = siteIdentityNodes({ IDEANEST_SITE_URL: 'https://staging.ideanest.az' });
    expect(organization?.url).toBe('https://staging.ideanest.az/');
  });

  it('names the site the same way every `<meta>` tag does', () => {
    const [organization, website] = siteIdentityNodes(env);

    expect(organization?.name).toBe(SITE_NAME);
    expect(website?.name).toBe(SITE_NAME);
    expect(website?.inLanguage).toBe(SITE_LANGUAGE);
  });

  it('points the site at the organisation by reference rather than repeating it', () => {
    const [, website] = siteIdentityNodes(env);
    expect(website?.publisher).toEqual({ '@id': `https://ideanest.az/${ORGANIZATION_FRAGMENT}` });
  });

  it('claims no logo, no social profile, and no search action', () => {
    for (const node of siteIdentityNodes(env)) {
      expect(node).not.toHaveProperty('logo');
      expect(node).not.toHaveProperty('sameAs');
      expect(node).not.toHaveProperty('potentialAction');
    }
  });
});
