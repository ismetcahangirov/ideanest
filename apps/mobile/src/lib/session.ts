import * as SecureStore from 'expo-secure-store';

/**
 * Where the session lives on a phone — §16's `Authorization: Bearer` with the
 * refresh token in secure storage.
 *
 * <h2>Why this is not `lib/storage.ts`</h2>
 *
 * The web keeps its refresh token in a `SameSite=Strict; HttpOnly` cookie, which
 * JavaScript on the page cannot read at all. A phone has no equivalent, so the
 * nearest true thing is the platform keychain: Keychain Services on iOS, the
 * Android Keystore behind `EncryptedSharedPreferences`. Both are backed by
 * hardware on any device this application supports, and both survive an
 * application update while MMKV's plain file does not deserve to hold a
 * credential in the first place.
 *
 * <h2>Access token in memory, refresh token on disk</h2>
 *
 * The access token lasts fifteen minutes (§16.2) and is never written down. A
 * fifteen-minute credential in persistent storage is a credential that outlives
 * the reason it existed: the process restarting is exactly the moment to go and
 * get a fresh one, and the refresh token is what makes that free.
 */

const REFRESH_TOKEN_KEY = 'ideanest.refresh-token';

let accessToken: string | null = null;

/** The access token this process is holding, if any. */
export function currentAccessToken(): string | null {
  return accessToken;
}

/** Remembers an access token for the life of the process. Never persisted. */
export function rememberAccessToken(token: string | null): void {
  accessToken = token;
}

/** The refresh token from the keychain, or null when nobody is signed in. */
export async function storedRefreshToken(): Promise<string | null> {
  try {
    return await SecureStore.getItemAsync(REFRESH_TOKEN_KEY);
  } catch {
    /*
     * A keychain read can fail for reasons that are not "no session": a device
     * locked with `WHEN_UNLOCKED` set, or a keystore invalidated because the
     * user changed their screen lock. Treating that as signed-out is the safe
     * reading -- it asks somebody to sign in again, rather than throwing on a
     * screen that only wanted to know whether to show a Saved tab.
     */
    return null;
  }
}

/** Stores, or clears, the refresh token. */
export async function storeRefreshToken(token: string | null): Promise<void> {
  if (token === null) {
    await SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY);
    return;
  }
  await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, token, {
    keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
  });
}

/** Forgets everything about the current session, in both places it is kept. */
export async function endSession(): Promise<void> {
  rememberAccessToken(null);
  await storeRefreshToken(null);
}
