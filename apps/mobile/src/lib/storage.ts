import { createMMKV } from 'react-native-mmkv';

/**
 * The device's key/value store — §14.3's MMKV, behind the one seam that keeps it
 * testable.
 *
 * <h2>Why an interface rather than the MMKV instance</h2>
 *
 * MMKV is a native module with no JavaScript implementation. That is the reason
 * to use it — a synchronous read is what lets `lib/offline.ts` hydrate the query
 * cache before the first frame instead of after it — and it is also the reason
 * every module that touched it directly would be untestable outside a simulator.
 * The interface is four methods wide because that is all the persister needs.
 *
 * <h2>What must never be stored here</h2>
 *
 * **No tokens.** MMKV is fast, unencrypted by default, and readable by anything
 * with the application's sandbox. §12 puts the refresh token in the platform
 * keychain, which is what `lib/session.ts` uses. What belongs here is the cache:
 * campaign summaries a backer has already been shown, and which of them they
 * saved. Losing all of it costs one network round trip.
 */
export interface KeyValueStore {
  getString(key: string): string | undefined;
  set(key: string, value: string): void;
  /**
   * Named `remove` because that is what MMKV v4 calls it. A wrapper that renamed
   * it to `delete` would read better and would be the reason somebody later
   * "fixes" the implementation by calling a method that does not exist.
   */
  remove(key: string): void;
  getAllKeys(): readonly string[];
}

/**
 * The application's store.
 *
 * The id is explicit rather than defaulted so that a second store can be added
 * later without the first one silently becoming "the other one".
 */
export const deviceStore: KeyValueStore = createMMKV({ id: 'ideanest' });

/** A store that lives only as long as the process. For tests, and for nothing else. */
export function memoryStore(): KeyValueStore {
  const entries = new Map<string, string>();
  return {
    getString: (key) => entries.get(key),
    set: (key, value) => void entries.set(key, value),
    remove: (key) => void entries.delete(key),
    getAllKeys: () => [...entries.keys()],
  };
}
