import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * §4.1's A-04 and A-05 — issue #273.
 *
 * WHAT THESE COVER:
 *
 *   - **an unconfigured provider is not offered.** §17.1: its endpoint answers 501, and a
 *     button that always fails is worst of all on the screen somebody has not got past yet.
 *   - the nonce is unpredictable and URL-safe. A nonce that repeats binds nothing.
 *   - **a provider sign-in returns a challenge like any other**, because `TokenController`
 *     answers both paths through one `respondTo`. This is the assertion that stops a provider
 *     button quietly making two-factor advisory.
 *   - the ID token reaches the service unmodified, and Apple's one-time name travels with it.
 *
 * The client identifiers are read at module scope, so every case re-imports the module with
 * `vi.resetModules()` after setting the environment — assigning to `process.env` afterwards
 * would change nothing.
 */

const ORIGINAL_ENV = { ...process.env };

async function load(env: Record<string, string | undefined>) {
  vi.resetModules();
  process.env = { ...ORIGINAL_ENV, ...env };
  return import('./providers');
}

beforeEach(() => {
  vi.restoreAllMocks();
});

afterEach(() => {
  process.env = { ...ORIGINAL_ENV };
});

describe('configuredProviders', () => {
  it('offers nothing when neither identifier is set', async () => {
    const { configuredProviders } = await load({
      NEXT_PUBLIC_GOOGLE_CLIENT_ID: undefined,
      NEXT_PUBLIC_APPLE_CLIENT_ID: undefined,
    });

    expect(configuredProviders()).toEqual([]);
  });

  it('offers only the provider that is configured', async () => {
    const { configuredProviders } = await load({
      NEXT_PUBLIC_GOOGLE_CLIENT_ID: 'google-client.apps.googleusercontent.com',
      NEXT_PUBLIC_APPLE_CLIENT_ID: undefined,
    });

    expect(configuredProviders()).toEqual([
      { id: 'google', label: 'Google', clientId: 'google-client.apps.googleusercontent.com' },
    ]);
  });

  it('treats a blank identifier as unset rather than as a provider named ""', async () => {
    // An empty variable is what a deployment that declared the name and never filled it in
    // produces, and it is the one shape most likely to reach production.
    const { configuredProviders } = await load({
      NEXT_PUBLIC_GOOGLE_CLIENT_ID: '   ',
      NEXT_PUBLIC_APPLE_CLIENT_ID: 'az.ideanest.web',
    });

    expect(configuredProviders().map((provider) => provider.id)).toEqual(['apple']);
  });
});

describe('generateNonce', () => {
  it('is URL-safe and carries no padding', async () => {
    const { generateNonce } = await load({});
    expect(generateNonce()).toMatch(/^[A-Za-z0-9_-]+$/u);
  });

  it('does not repeat', async () => {
    const { generateNonce } = await load({});
    const values = new Set(Array.from({ length: 50 }, () => generateNonce()));
    expect(values.size).toBe(50);
  });
});

describe('signInWithProvider', () => {
  function respond(body: unknown, status = 200): void {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify(body), {
          status,
          headers: { 'content-type': 'application/json' },
        }),
      ),
    );
  }

  it('posts the token to the provider’s own path and reports a session', async () => {
    respond({ accessToken: 'a-fifteen-minute-token' });
    const { signInWithProvider } = await load({});

    const outcome = await signInWithProvider({
      provider: 'google',
      idToken: 'the.id.token',
      nonce: 'a-nonce',
    });

    expect(outcome).toEqual({ kind: 'signed-in' });

    const [path, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/v1/auth/oauth/google');
    expect(JSON.parse(String(init.body))).toEqual({
      idToken: 'the.id.token',
      nonce: 'a-nonce',
    });
    // Same-origin, or the SameSite=Strict refresh cookie is silently never stored.
    expect(init.credentials).toBe('same-origin');
  });

  it('raises the two-factor challenge rather than reporting a session', async () => {
    respond({ twoFactorRequired: true, challenge: 'the-challenge', expiresInSeconds: 300 });
    const { signInWithProvider } = await load({});

    expect(
      await signInWithProvider({ provider: 'apple', idToken: 't', nonce: 'n' }),
    ).toEqual({ kind: 'two-factor-required', challenge: 'the-challenge', expiresInSeconds: 300 });
  });

  it('forwards Apple’s one-time name, and omits it when there is none', async () => {
    respond({ accessToken: 'token' });
    const { signInWithProvider } = await load({});

    await signInWithProvider({ provider: 'apple', idToken: 't', nonce: 'n', name: 'Aysel Q' });
    const withName = JSON.parse(
      String((vi.mocked(fetch).mock.calls[0] as [string, RequestInit])[1].body),
    );
    expect(withName.name).toBe('Aysel Q');

    await signInWithProvider({ provider: 'apple', idToken: 't', nonce: 'n' });
    const without = JSON.parse(
      String((vi.mocked(fetch).mock.calls[1] as [string, RequestInit])[1].body),
    );
    expect(without).not.toHaveProperty('name');
  });

  it('throws the service’s refusal rather than swallowing it', async () => {
    respond(
      { title: 'Verify your address first', detail: 'Check the email already in your inbox.' },
      409,
    );
    const { signInWithProvider } = await load({});

    await expect(
      signInWithProvider({ provider: 'google', idToken: 't', nonce: 'n' }),
    ).rejects.toMatchObject({ status: 409 });
  });
});
