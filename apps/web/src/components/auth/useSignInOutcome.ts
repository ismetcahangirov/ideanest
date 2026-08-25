'use client';

import { useCallback, useState } from 'react';
import { useRouter } from '../../i18n/navigation';
import type { SignInOutcome } from '../../lib/auth/api';
import { useSession } from '../session/SessionProvider';

/**
 * What happens after a sign-in succeeds, in one place — §4.1 A-03, A-04, A-05, A-07.
 *
 * <h2>Three screens reach this and they must not disagree</h2>
 *
 * A session can now be created from the password form, from a Google button, or from an Apple
 * button, and each of those exists on both `/sign-in` and `/register`. `TokenController`
 * answers all of them through one `respondTo`, which means **any of them can return a
 * two-factor challenge rather than a session** — an account with a second factor confirmed
 * gets one whichever way the first factor was proved, and its own comment says why: "letting a
 * provider button skip it would make two-factor advisory, which is the same as not having it."
 *
 * A component that handled the challenge branch in one place and forgot it in another would
 * not fail loudly. It would call `refresh()`, find no session, and leave somebody on a sign-in
 * form that appeared to do nothing. So the branch is here, once.
 *
 * <h2>Refresh, then navigate</h2>
 *
 * The shell reads its state from `SessionProvider`. Navigating first would land the reader on
 * a page whose header still says Sign in — briefly, and exactly at the moment they are
 * checking whether it worked.
 *
 * `replace`, not `push`: the sign-in form is not a page anybody should return to with Back
 * once they are through it.
 */

export interface PendingChallenge {
  readonly value: string;
  readonly expiresInSeconds: number;
}

export interface SignInOutcomeState {
  /** Non-null while a second factor is owed. The form renders the challenge step instead. */
  readonly challenge: PendingChallenge | null;
  /** Acts on an outcome: either finishes, or raises the challenge. */
  readonly settle: (outcome: SignInOutcome) => Promise<void>;
  /** Reads the session and navigates. The challenge step calls it once it has a session. */
  readonly finish: () => Promise<void>;
  /** Abandons a challenge and returns to the first step. */
  readonly clearChallenge: () => void;
}

export function useSignInOutcome(returnTo: string): SignInOutcomeState {
  const router = useRouter();
  const { refresh } = useSession();
  const [challenge, setChallenge] = useState<PendingChallenge | null>(null);

  const finish = useCallback(async () => {
    await refresh();
    router.replace(returnTo);
  }, [refresh, router, returnTo]);

  const settle = useCallback(
    async (outcome: SignInOutcome) => {
      if (outcome.kind === 'two-factor-required') {
        setChallenge({ value: outcome.challenge, expiresInSeconds: outcome.expiresInSeconds });
        return;
      }
      await finish();
    },
    [finish],
  );

  const clearChallenge = useCallback(() => setChallenge(null), []);

  return { challenge, settle, finish, clearChallenge };
}
