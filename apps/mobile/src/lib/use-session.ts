import { useEffect, useState } from 'react';
import { storedRefreshToken } from './session';

/**
 * Whether anybody is signed in on this device.
 *
 * <h2>Why a hook rather than a provider</h2>
 *
 * Two screens ask, and both ask the same question of the keychain, which is a
 * fast local read. A context provider would add a tree-wide re-render for a
 * value that changes at most twice in a session, and it would have to be
 * threaded through the root layout in front of the query client — where it would
 * delay the first frame on a cold start behind a keychain read that the first
 * frame does not need.
 *
 * <h2>What this is NOT</h2>
 *
 * It is not authentication. It answers "is there a refresh token here", which is
 * enough to decide whether to show a sign-in prompt or a list, and nothing more:
 * the token can be revoked, and only the service knows. A screen that showed
 * private data on the strength of this alone would be showing it on the strength
 * of a file on the device. Every read that matters still goes through
 * `api/client.ts` and is still refused by the service without a valid bearer.
 *
 * Sign-in itself is not built. §4.2's mobile authentication is `#58`'s
 * neighbourhood and blocked on the payment provider decision (#60); until then
 * the honest surface is a prompt rather than a form that cannot complete.
 */
export interface Session {
  /** Null while the keychain is still being read — not `false`, which would flash a prompt. */
  readonly signedIn: boolean;
  readonly checked: boolean;
}

export function useSession(): Session {
  const [state, setState] = useState<Session>({ signedIn: false, checked: false });

  useEffect(() => {
    let live = true;
    void storedRefreshToken().then((token) => {
      if (live) setState({ signedIn: token !== null, checked: true });
    });
    return () => {
      live = false;
    };
  }, []);

  return state;
}
