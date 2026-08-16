import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authorizedFetch } from '../api/client';
import { listCategories } from './api';

/**
 * How the taxonomy is read.
 *
 * The endpoint localises: each taxon arrives with a `name` already resolved
 * against the request's `Accept-Language`, and with `nameAz` / `nameEn`, which
 * are the interim columns of V6 on their way out under expand-then-contract.
 * This client must read the first and ignore the last two — the API cannot drop
 * a field while a client still reads it, so a reader that quietly falls back to
 * `nameEn` would keep those columns alive for ever without anybody noticing.
 */

vi.mock('../api/client', () => ({
  authorizedFetch: vi.fn(),
  publicFetch: vi.fn(),
}));

const authorizedFetchMock = vi.mocked(authorizedFetch);

function respondWith(body: unknown): void {
  authorizedFetchMock.mockResolvedValue(
    new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

describe('listCategories', () => {
  beforeEach(() => {
    authorizedFetchMock.mockReset();
  });

  it('reads the resolved name, not the interim columns', async () => {
    respondWith([
      {
        id: 'category-1',
        slug: 'art',
        name: 'İncəsənət',
        names: { az: 'İncəsənət', en: 'Art' },
        nameAz: 'İncəsənət',
        nameEn: 'Art',
        subcategories: [
          {
            id: 'sub-1',
            slug: 'illustration',
            name: 'İllüstrasiya',
            names: { az: 'İllüstrasiya', en: 'Illustration' },
            nameAz: 'İllüstrasiya',
            nameEn: 'Illustration',
          },
        ],
      },
    ]);

    const categories = await listCategories();

    // The server chose the language. Choosing again here would be a second,
    // disagreeing answer to one question.
    expect(categories).toEqual([
      {
        id: 'category-1',
        slug: 'art',
        name: 'İncəsənət',
        subcategories: [{ id: 'sub-1', slug: 'illustration', name: 'İllüstrasiya' }],
      },
    ]);
  });

  it('falls back to the slug rather than putting undefined in a select', async () => {
    // Should not happen: `name` is resolved through requested locale, then `az`,
    // then the slug, server-side. This is what stops a malformed response from
    // rendering an empty <option> a creator cannot tell apart from a real one.
    respondWith([{ id: 'category-1', slug: 'crafts' }]);

    expect(await listCategories()).toEqual([
      { id: 'category-1', slug: 'crafts', name: 'crafts', subcategories: [] },
    ]);
  });
});
