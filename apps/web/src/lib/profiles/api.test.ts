import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authorizedFetch, publicFetch } from '../api/client';
import {
  listBackedProjects,
  listCreatedProjects,
  probeProfileVisibility,
  profileHref,
  setProfileVisibility,
} from './api';

/**
 * §4.2's profile client — issue #274.
 *
 * WHAT THESE COVER, and why each is worth a test rather than a comment:
 *
 *   - **the visibility probe is the whole of P-07's read.** There is no `GET` for the setting,
 *     so the switch on `/settings/privacy` positions itself from the public endpoint's status
 *     code. If 404 ever stopped meaning "hidden" here, the switch would silently show the
 *     opposite of the truth and the next press would write over somebody's choice.
 *   - **the probe must be anonymous.** `publicFetch` attaches the access token when there is
 *     one, and the question being asked is what a *stranger* sees. A request the service could
 *     recognise would answer for the owner, and the panel would say "public" about a profile
 *     nobody else can read.
 *   - the paginated lists rename `projects` to `items`, which is the one line reconciling the
 *     service's naming with `useCursorList`'s.
 */

vi.mock('../api/client', () => ({
  publicFetch: vi.fn(),
  authorizedFetch: vi.fn(),
}));

const publicMock = vi.mocked(publicFetch);
const authorizedMock = vi.mocked(authorizedFetch);

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the profile lists', () => {
  it('reads the service’s `projects` array as the paginator’s `items`', async () => {
    publicMock.mockResolvedValue(
      json({ projects: [{ id: 'p1', title: 'A thing' }], nextCursor: 'abc' }),
    );

    const page = await listCreatedProjects('aysel');

    expect(page.items).toHaveLength(1);
    expect(page.nextCursor).toBe('abc');
  });

  it('reads a missing array as an empty page rather than throwing', async () => {
    // `default-property-inclusion: non_null` means an empty list may arrive as no key at all,
    // and a profile with no campaigns is the common case rather than an error.
    publicMock.mockResolvedValue(json({}));

    const page = await listBackedProjects('aysel');

    expect(page.items).toEqual([]);
    expect(page.nextCursor).toBeNull();
  });

  it('passes the opaque cursor back unread', async () => {
    publicMock.mockResolvedValue(json({ projects: [], nextCursor: null }));

    await listCreatedProjects('aysel', 'eyJpZCI6MX0=');

    const [path] = publicMock.mock.calls[0] ?? [];
    expect(path).toContain('cursor=eyJpZCI6MX0%3D');
  });

  it('asks the backed list of the right person, escaping the slug', async () => {
    publicMock.mockResolvedValue(json({ projects: [], nextCursor: null }));

    await listBackedProjects('a person/../admin');

    const [path] = publicMock.mock.calls[0] ?? [];
    expect(path).toContain('/v1/users/a%20person%2F..%2Fadmin/backed');
  });

  it('does not swallow a refusal', async () => {
    publicMock.mockResolvedValue(json({ title: 'Not found' }, 404));

    await expect(listCreatedProjects('nobody')).rejects.toBeDefined();
  });
});

describe('the visibility probe', () => {
  it('reads a profile that answers as public', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ slug: 'aysel' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(probeProfileVisibility('aysel')).resolves.toBe('PUBLIC');
  });

  it('reads a 404 as hidden, because for the caller’s own slug that is the only other answer', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(probeProfileVisibility('aysel')).resolves.toBe('PRIVATE');
  });

  it('answers null for anything else, rather than guessing a position for the switch', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 503 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(probeProfileVisibility('aysel')).resolves.toBeNull();
  });

  it('sends no credentials, so the answer is the one a stranger gets', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ slug: 'aysel' }));
    vi.stubGlobal('fetch', fetchMock);

    await probeProfileVisibility('aysel');

    const [, init] = fetchMock.mock.calls[0] ?? [];
    expect(init).toMatchObject({ credentials: 'omit', cache: 'no-store' });
    // `publicFetch` would have attached the token. This must not use it.
    expect(publicMock).not.toHaveBeenCalled();
  });
});

describe('setting the visibility', () => {
  it('PATCHes the caller’s own account, with no identifier in the path', async () => {
    authorizedMock.mockResolvedValue(new Response(null, { status: 204 }));

    await setProfileVisibility('PRIVATE');

    const [path, init] = authorizedMock.mock.calls[0] ?? [];
    expect(path).toBe('/v1/me/profile-visibility');
    expect(init?.method).toBe('PATCH');
    expect(init?.body).toBe(JSON.stringify({ visibility: 'PRIVATE' }));
  });

  it('throws on a refusal rather than reporting a change that did not happen', async () => {
    authorizedMock.mockResolvedValue(json({ title: 'Forbidden' }, 403));

    await expect(setProfileVisibility('PUBLIC')).rejects.toBeDefined();
  });
});

describe('profileHref', () => {
  it('escapes a slug rather than pasting it into a path', () => {
    expect(profileHref('a/b')).toBe('/u/a%2Fb');
  });
});
