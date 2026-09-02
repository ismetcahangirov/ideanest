import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authorizedFetch } from '../api/client';
import {
  MAX_IDENTIFIERS,
  identifiersIn,
  lookUpNames,
  namesFrom,
  readDirectory,
} from './directory';

vi.mock('../api/client', () => ({ authorizedFetch: vi.fn() }));

const fetchMock = vi.mocked(authorizedFetch);

function answered(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: async () => body,
  } as unknown as Response;
}

function ids(count: number, prefix = 'a'): string[] {
  const made: string[] = [];
  for (let index = 0; index < count; index += 1) made.push(`${prefix}-${index}`);
  return made;
}

beforeEach(() => {
  vi.clearAllMocks();
  fetchMock.mockResolvedValue(answered({ accounts: [], projects: [] }));
});

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * The console's identifier lookup — issue #402.
 *
 * <p>What is worth covering here is the arithmetic, because it is the part that fails
 * silently. A request that goes over the service's ceiling comes back as
 * `TOO_MANY_IDENTIFIERS`, which the caller swallows — so the whole screen renders fragments
 * and nothing says why. A batching loop that fails to make progress hangs the render.
 */
describe('the console directory client', () => {
  it('asks about nothing without making a request', async () => {
    await readDirectory([], []);

    // A screen with no identifiers on it — an empty queue, a filtered list that matched
    // nothing — must not cost a round trip to find that out.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('repeats the parameter rather than joining the identifiers', async () => {
    await readDirectory(['one', 'two'], ['three']);

    // `?account=a,b` is one identifier whose text contains a comma, and Spring binds the
    // repeated form to a list without a converter.
    const [path] = fetchMock.mock.calls[0]!;
    expect(path).toContain('account=one&account=two');
    expect(path).toContain('project=three');
  });

  it('splits a request that would go over the service ceiling', async () => {
    await lookUpNames(ids(MAX_IDENTIFIERS + 20), []);

    /*
     * Over the ceiling the service refuses rather than truncating, and the caller swallows
     * the failure — so a screen that asked for one identifier too many would render every
     * one of them as a fragment with nothing to say why.
     */
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('counts both lists against one ceiling, because the limit is the request', async () => {
    await lookUpNames(ids(80, 'account'), ids(80, 'project'));

    // A hundred and sixty identifiers is two requests, not one of a hundred and eighty
    // parameters — the bound behind the number is the eight-kilobyte header block, which
    // does not care which list a parameter came from.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const [path] of fetchMock.mock.calls) {
      expect((String(path).match(/(account|project)=/g) ?? []).length).toBeLessThanOrEqual(
        MAX_IDENTIFIERS,
      );
    }
  });

  it('collects every batch into one answer', async () => {
    fetchMock
      .mockResolvedValueOnce(
        answered({ accounts: [{ id: 'a-0', name: 'First', slug: 'first' }], projects: [] }),
      )
      .mockResolvedValueOnce(
        answered({ accounts: [{ id: 'a-1', name: 'Second', slug: 'second' }], projects: [] }),
      );

    const directory = await lookUpNames(ids(MAX_IDENTIFIERS + 1), []);

    expect(directory.accounts).toHaveLength(2);
    expect(namesFrom(directory).accounts.get('a-1')?.name).toBe('Second');
  });
});

describe('the identifiers a screen asks about', () => {
  it('drops nulls, blanks and repeats', () => {
    // A queue of twenty payouts to one creator asks about that creator twenty times, and
    // the request should not.
    expect(identifiersIn(['one', null, 'one', '', undefined, 'two'])).toEqual(['one', 'two']);
  });

  it('keeps the order they were first seen in, so a request is reproducible', () => {
    expect(identifiersIn(['b', 'a', 'b'])).toEqual(['b', 'a']);
  });
});
