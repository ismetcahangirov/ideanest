import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import openapiTS, { astToString } from 'openapi-typescript';
import { createApiClient } from './client';

const CONTRACT = fileURLToPath(new URL('../../../apps/api/openapi.json', import.meta.url));
const GENERATED = fileURLToPath(new URL('./schema.ts', import.meta.url));

/**
 * The generated client and the published contract, checked against each other.
 *
 * `apps/api/openapi.json` is asserted to describe the service by `OpenApiContractTests` on
 * the Java side. This is the other half of the chain: that `schema.ts` describes that
 * document. Both together are what make a renamed response field a compile error in
 * `@ideanest/web` rather than an `undefined` on somebody's screen.
 *
 * Without this, the failure mode is silent and slow — the contract is regenerated, the
 * client is not, and the types keep describing a service that has moved. Nothing fails
 * until somebody reads a field that no longer exists.
 */
describe('the generated schema', () => {
  it('is the one openapi.json produces', async () => {
    const ast = await openapiTS(new URL(`file://${CONTRACT.replaceAll('\\', '/')}`), {
      rootTypes: true,
    });
    const expected = astToString(ast);
    const committed = await readFile(GENERATED, 'utf8');

    /*
     * Line endings are normalised before comparing. The repository is developed on Windows
     * and CI runs on Linux; `core.autocrlf` therefore decides what is on disk, and a
     * comparison that failed on that would fail on every machine but the one that last ran
     * the generator — which is the shape of a check people learn to ignore.
     */
    expect(normalise(committed)).toBe(normalise(expected));
  }, 30_000);
});

/**
 * The hand-written half. Small enough to check completely, and worth checking because every
 * request the platform makes goes through it.
 */
describe('the client', () => {
  it('substitutes and encodes path parameters', async () => {
    const requested: string[] = [];
    const client = createApiClient({
      baseUrl: 'https://api.test',
      fetch: recorder(requested),
    });

    await client.get('/v1/projects/{creatorSlug}/{projectSlug}', {
      path: { creatorSlug: 'ayan', projectSlug: 'coffee table' },
    });

    expect(requested).toEqual(['https://api.test/v1/projects/ayan/coffee%20table']);
  });

  it('repeats an array parameter rather than joining it', async () => {
    const requested: string[] = [];
    const client = createApiClient({ fetch: recorder(requested) });

    // `DiscoveryQueryBinder` binds a MultiValueMap, so `?tag=a&tag=b` is two tags and
    // `?tag=a,b` is one tag whose name contains a comma.
    await client.get('/v1/discover', { query: { tag: ['ceramics', 'design'] } });

    expect(requested).toEqual(['/v1/discover?tag=ceramics&tag=design']);
  });

  it('drops a filter that was not set', async () => {
    const requested: string[] = [];
    const client = createApiClient({ fetch: recorder(requested) });

    await client.get('/v1/discover', { query: { cursor: undefined, limit: 24 } });

    expect(requested).toEqual(['/v1/discover?limit=24']);
  });

  it('throws the problem detail rather than returning it', async () => {
    const client = createApiClient({
      fetch: async () =>
        new Response(JSON.stringify({ code: 'PROJECT_NOT_FOUND', detail: 'That project does not exist.' }), {
          status: 404,
          headers: { 'content-type': 'application/problem+json' },
        }),
    });

    await expect(
      client.get('/v1/projects/{creatorSlug}/{projectSlug}', {
        path: { creatorSlug: 'ayan', projectSlug: 'gone' },
      }),
    ).rejects.toMatchObject({ status: 404, problem: { code: 'PROJECT_NOT_FOUND' } });
  });

  it('refuses to send a path with a segment missing', async () => {
    const client = createApiClient({ fetch: async () => new Response('{}', { status: 200 }) });

    await expect(
      // A caller that lost a parameter would otherwise request the literal
      // `/v1/projects/ayan/{projectSlug}` and be answered 404 by the service, which is a bug
      // report about the wrong thing.
      client.get('/v1/projects/{creatorSlug}/{projectSlug}', {
        path: { creatorSlug: 'ayan' } as never,
      }),
    ).rejects.toThrow('{projectSlug}');
  });
});

function recorder(requested: string[]) {
  return async (url: string): Promise<Response> => {
    requested.push(url);
    return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
  };
}

/**
 * Line endings, trailing whitespace, and the banner.
 *
 * The banner is the CLI's — `openapiTS` called as a library does not emit it — so a
 * comparison that kept it would fail on a difference between two ways of running the same
 * generator rather than on a difference in the contract. Everything after it is the part
 * that describes the service.
 */
function normalise(source: string): string {
  return source
    .replaceAll('\r\n', '\n')
    .replace(/^\/\*\*[\s\S]*?\*\/\n+/, '')
    .trimEnd();
}
