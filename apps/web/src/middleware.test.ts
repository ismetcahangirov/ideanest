import { NextRequest, NextResponse } from 'next/server';
import { describe, expect, it, vi } from 'vitest';
import { LOCALE_COOKIE } from './lib/i18n/locale';

/*
 * next-intl's middleware is stubbed rather than run.
 *
 * Two reasons, and the second is the real one. It resolves `next/server` through its own
 * package directory, which this repository's module layout does not satisfy under Vitest —
 * but even if it did, running it here would be testing next-intl. What this file is
 * responsible for is the code in `middleware.ts`: which requests get a redirect, where to,
 * with what status, and — the loop guard — which ones are passed through untouched. The stub
 * makes "passed through" observable as a response with no `location` on it.
 */
vi.mock('next-intl/middleware', () => ({
  default: () => () => NextResponse.next(),
}));

const { default: middleware } = await import('./middleware');

/**
 * What happens to a request with no language in its path — issue #123.
 *
 * <h2>Why these are worth asserting</h2>
 *
 * The redirect is the only place a cookie is still read, and every one of its properties is
 * a defect that does not look like one: a 308 instead of a 307 pins a reader's first language
 * in their own browser cache forever, a dropped query string loses a `?category=` a link was
 * shared with, and a path that already names a language being redirected again is an
 * infinite loop that only reproduces for people who have a cookie set.
 */
function request(path: string, cookie?: string): NextRequest {
  const url = `https://ideanest.az${path}`;
  const headers = cookie === undefined ? undefined : { cookie: `${LOCALE_COOKIE}=${cookie}` };
  return new NextRequest(url, headers === undefined ? undefined : { headers });
}

describe('the locale middleware', () => {
  it('sends the bare path to the default language when nothing is stored', () => {
    const response = middleware(request('/'));

    expect(response.status).toBe(307);
    expect(response.headers.get('location')).toBe('https://ideanest.az/en');
  });

  it('sends the bare path to the language the reader last chose', () => {
    const response = middleware(request('/', 'az'));

    expect(response.headers.get('location')).toBe('https://ideanest.az/az');
  });

  it('never answers with a permanent redirect', () => {
    /*
     * A 308 is cached by the browser itself. One recorded from `/` to `/en` would keep
     * sending a reader to English after they chose Azerbaijani — from their own cache,
     * without asking, in a way that no server-side change can reach and that clearing the
     * site's cookies does not fix.
     */
    for (const path of ['/', '/discover', '/projects/aysel/kilims']) {
      expect(middleware(request(path)).status).toBe(307);
    }
  });

  it('keeps the rest of the path and the query string', () => {
    const response = middleware(request('/discover?category=games&page=2', 'ru'));

    expect(response.headers.get('location')).toBe(
      'https://ideanest.az/ru/discover?category=games&page=2',
    );
  });

  it('does not put a trailing slash on the language root', () => {
    /* `/az/` and `/az` would be two addresses for one page. */
    expect(middleware(request('/', 'tr')).headers.get('location')).toBe('https://ideanest.az/tr');
  });

  it('falls back to English rather than trusting a cookie a reader can edit', () => {
    for (const value of ['xx', '', '../../etc/passwd', 'EN']) {
      expect(middleware(request('/', value)).headers.get('location')).toBe(
        'https://ideanest.az/en',
      );
    }
  });

  it('leaves a path that already names a language alone', () => {
    /*
     * The loop guard. next-intl's middleware answers these, and what matters here is that
     * this file does not send them round again — a second redirect on `/az/discover` would
     * be an infinite one, reproducing only for readers who have a cookie set.
     */
    for (const path of ['/az', '/en/discover', '/ru/projects/aysel/kilims', '/tr/settings']) {
      expect(middleware(request(path, 'az')).headers.get('location')).toBeNull();
    }
  });
});
