import { createApiClient, type ApiClient } from '@ideanest/api-client';
import { apiOrigin, deviceLocale } from './config';
import { currentAccessToken } from '../lib/session';

/**
 * The service, as this application talks to it.
 *
 * <h2>Absolute URLs, unlike the web client</h2>
 *
 * `apps/web` calls `/v1` as a relative path so that the browser treats it as
 * same-origin and attaches a `SameSite=Strict` cookie. There is no document
 * here, no origin, and no rewrite, so every request goes straight to the origin
 * the build was given. That also means there is no cookie: the session is a
 * bearer token from the keychain, which is what `lib/session.ts` exists for.
 *
 * <h2>Why the client is built per request rather than once</h2>
 *
 * `createApiClient` takes its headers at construction, and two of ours change:
 * the access token when a refresh lands, and the language when somebody changes
 * the phone's. A module-level singleton would capture whatever was true at first
 * import — which, for the language, is before the first screen has rendered.
 * Constructing one is an object literal and a closure; it is not worth caching
 * something that would be wrong.
 */
export function api(): ApiClient {
  const token = currentAccessToken();
  return createApiClient({
    baseUrl: apiOrigin(),
    headers: {
      'Accept-Language': deviceLocale(),
      ...(token === null ? {} : { Authorization: `Bearer ${token}` }),
    },
  });
}
