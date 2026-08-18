import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  ROUTE_PATTERNS,
  UNRECOGNISED_ROUTE,
  isKnownRoutePattern,
  routePatternOf,
} from './route-pattern';

/** From `process.cwd()`; see the note in `metrics.test.ts`. */
const budgets: { routes: Record<string, number> } = JSON.parse(
  readFileSync(resolve(process.cwd(), 'performance/budgets.json'), 'utf8'),
);

describe('the pattern list', () => {
  /*
   * A pattern invented here would be a row in the field summary that no bundle
   * budget corresponds to, and the two tables are meant to be read side by side.
   * A subset rather than an equality: `/_not-found` has a budget and can have no
   * field pattern, because a 404's pathname is whatever was typed.
   */
  it('names only routes the bundle budgets also know', () => {
    for (const pattern of ROUTE_PATTERNS) {
      expect(Object.keys(budgets.routes)).toContain(pattern);
    }
  });

  it('has no route that would collide with another', () => {
    expect(new Set(ROUTE_PATTERNS).size).toBe(ROUTE_PATTERNS.length);
  });
});

describe('routePatternOf', () => {
  it('maps a concrete path onto its pattern', () => {
    expect(routePatternOf('/discover')).toBe('/discover');
    expect(routePatternOf('/settings/sessions')).toBe('/settings/sessions');
    expect(routePatternOf('/projects/new')).toBe('/projects/new');
  });

  /*
   * THE POINT OF THE WHOLE MODULE. A campaign identifier names a campaign, the
   * campaign names a creator, and a run of them in order names what one visitor
   * looked at.
   */
  it('never lets a campaign identifier through', () => {
    const id = '019432f1-2c4a-7bb1-9f7e-0f21b7c9a4d2';
    expect(routePatternOf(`/projects/${id}/back`)).toBe('/projects/[id]/back');
    expect(routePatternOf(`/projects/${id}/edit/rewards`)).toBe('/projects/[id]/edit/rewards');
    expect(routePatternOf(`/projects/${id}/prelaunch`)).toBe('/projects/[id]/prelaunch');

    for (const pathname of [
      `/projects/${id}/back`,
      `/projects/${id}/edit/story`,
      `/projects/${id}/prelaunch`,
    ]) {
      expect(routePatternOf(pathname)).not.toContain(id);
    }
  });

  /*
   * `/discover?q=…` is whatever somebody typed into a search box. It is refused
   * rather than trimmed: a caller that passed a whole URL by mistake gets the
   * sentinel, not a cleaned-up version of their mistake.
   */
  it('refuses anything carrying a query string or a fragment', () => {
    expect(routePatternOf('/discover?q=vintage+watches')).toBe(UNRECOGNISED_ROUTE);
    expect(routePatternOf('/discover?category=games')).toBe(UNRECOGNISED_ROUTE);
    expect(routePatternOf('/discover#results')).toBe(UNRECOGNISED_ROUTE);
    expect(routePatternOf('https://ideanest.az/discover?q=x')).toBe(UNRECOGNISED_ROUTE);
  });

  it('reports an unknown path as the sentinel and never as itself', () => {
    for (const pathname of [
      '/',
      '/nope',
      '/projects',
      '/projects/abc/def/ghi',
      '/settings',
      '/v1/auth/sessions',
      '/projects/019432f1/back/extra',
      '',
      '////',
      '/PROJECTS/new',
      '../../etc/passwd',
    ]) {
      expect(routePatternOf(pathname)).toBe(UNRECOGNISED_ROUTE);
    }
  });

  it('is total: every answer is a declared pattern or the sentinel', () => {
    const inputs = [
      '/discover',
      '/discover/',
      '/projects/x/back',
      '/nonsense',
      '/%2e%2e/secret',
      '/projects//back',
    ];
    for (const pathname of inputs) {
      expect(isKnownRoutePattern(routePatternOf(pathname))).toBe(true);
    }
  });

  it('treats a trailing slash as the same route', () => {
    expect(routePatternOf('/discover/')).toBe('/discover');
    expect(routePatternOf('/projects/abc/back/')).toBe('/projects/[id]/back');
  });

  it('does not match a dynamic segment against nothing', () => {
    expect(routePatternOf('/projects//back')).toBe(UNRECOGNISED_ROUTE);
  });

  it('survives a non-string', () => {
    expect(routePatternOf(undefined as unknown as string)).toBe(UNRECOGNISED_ROUTE);
    expect(routePatternOf(null as unknown as string)).toBe(UNRECOGNISED_ROUTE);
  });
});
