import { describe, expect, it, vi } from 'vitest';
import { MAX_TAGS, handleRevalidate } from './endpoint';
import { DISCOVERY, campaignAddress, project } from './tags';

/**
 * `POST /api/cache/revalidate` — issue #127.
 *
 * WHAT THESE COVER:
 *
 *   - **a deployment with no secret refuses everything**, rather than allowing everything.
 *     A variable that went missing must fail loudly: an endpoint that evicts by name and
 *     asks for no proof is a request anybody can send in a loop to turn every cached render
 *     into an origin fetch, which is a denial-of-service against the service the cache exists
 *     to shield.
 *   - **the vocabulary is closed and an unknown tag is named**, not ignored. The realistic
 *     threat is a caller with the secret and a bug — an empty string, a wildcard — and the
 *     first two of those would evict the whole cache. A caller whose tag was silently dropped
 *     would believe the page had been refreshed and look for the fault on the wrong side.
 *   - **nothing is evicted by a call that was refused.** Every guard is checked before the
 *     first eviction, so a batch with one bad tag in it clears nothing.
 */

const SECRET = 'a-shared-secret-of-some-length';

function call(
  body: unknown,
  options: { readonly token?: string | null; readonly secret?: string | undefined } = {},
) {
  const revalidate = vi.fn();
  const headers = new Headers({ 'content-type': 'application/json' });
  const token = options.token === undefined ? SECRET : options.token;
  if (token !== null) headers.set('authorization', `Bearer ${token}`);

  const request = new Request('https://ideanest.az/api/cache/revalidate', {
    method: 'POST',
    headers,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });

  const outcome = handleRevalidate(request, {
    revalidate,
    secret: 'secret' in options ? options.secret : SECRET,
  });

  return { outcome, revalidate };
}

describe('the invalidation endpoint', () => {
  it('evicts the tags it was given and says which', async () => {
    const tags = [project('7b1c2d3e'), campaignAddress('ayan', 'studio')];
    const { outcome, revalidate } = call({ tags });

    expect((await outcome).status).toBe(200);
    expect((await outcome).body).toEqual({ revalidated: tags });
    expect(revalidate.mock.calls.map(([tag]) => tag)).toEqual(tags);
  });

  it('refuses every call when the deployment has no secret, and names the variable', async () => {
    const { outcome, revalidate } = call({ tags: [DISCOVERY] }, { secret: undefined });

    expect((await outcome).status).toBe(503);
    expect(String((await outcome).body['detail'])).toContain('IDEANEST_REVALIDATE_SECRET');
    expect(revalidate).not.toHaveBeenCalled();
  });

  it('refuses a call with no proof, a wrong one, or one of the wrong length', async () => {
    for (const token of [null, 'wrong', `${SECRET}x`, SECRET.slice(0, -1)]) {
      const { outcome, revalidate } = call({ tags: [DISCOVERY] }, { token });

      expect((await outcome).status, String(token)).toBe(401);
      expect(revalidate).not.toHaveBeenCalled();
    }
  });

  it('refuses a body that is not the shape it takes', async () => {
    for (const body of ['not json', { tags: 'discovery' }, { tags: [1, 2] }, ['discovery']]) {
      const { outcome, revalidate } = call(body);

      expect((await outcome).status, JSON.stringify(body)).toBe(400);
      expect(revalidate).not.toHaveBeenCalled();
    }
  });

  it('refuses an empty batch, because it is a call that meant to say something', async () => {
    expect((await call({ tags: [] }).outcome).status).toBe(400);
  });

  it('refuses a batch bigger than one campaign’s worth', async () => {
    const tags = Array.from({ length: MAX_TAGS + 1 }, (_unused, index) => project(`p${index}`));
    const { outcome, revalidate } = call({ tags });

    expect((await outcome).status).toBe(400);
    expect((await outcome).body['error']).toBe('too-many-tags');
    expect(revalidate).not.toHaveBeenCalled();
  });

  /** An empty string and a wildcard are the two that would evict everything. */
  it('names an unrecognised tag and evicts nothing at all', async () => {
    const { outcome, revalidate } = call({ tags: [project('7b1c'), '', '*', 'project:'] });

    expect((await outcome).status).toBe(400);
    expect((await outcome).body).toEqual({ error: 'unknown-tags', tags: ['', '*', 'project:'] });
    expect(revalidate).not.toHaveBeenCalled();
  });

  /**
   * The relay on the other side retries, so the same batch arrives more than once and
   * sometimes half-overlapping. Evicting a tag twice costs one origin fetch, which is why
   * there is no idempotency store here — but sending it twice in one call is a caller bug
   * worth absorbing rather than repeating.
   */
  it('evicts a repeated tag once', async () => {
    const { outcome, revalidate } = call({ tags: [DISCOVERY, DISCOVERY] });

    expect((await outcome).status).toBe(200);
    expect(revalidate).toHaveBeenCalledTimes(1);
  });
});
