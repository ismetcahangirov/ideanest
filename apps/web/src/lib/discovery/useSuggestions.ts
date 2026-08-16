'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../api/problem';
import { getSuggestions, isAnswerable, type Suggestion } from './suggest';

/**
 * D-02's suggestions for what is being typed, debounced and immune to
 * out-of-order responses.
 *
 * THE DEBOUNCE IS 200ms, and the number is chosen rather than picked. Measured
 * inter-keystroke intervals for continuous typing sit around 150–200ms, so a
 * shorter delay fires mid-word and issues a request per keystroke — the thing
 * the endpoint's own Javadoc calls "one request per keystroke, per visitor" and
 * caches for five minutes to survive. A longer one is felt: past roughly 300ms
 * the list arrives after the reader has stopped and looked, which reads as the
 * control being slow rather than as the reader being fast. 200ms sits under
 * that and past the burst.
 *
 * OUT-OF-ORDER RESPONSES ARE DEFEATED TWICE, deliberately.
 *
 *   1. Every request carries an `AbortController` that is aborted the moment a
 *      newer fragment supersedes it. This is the part that saves the network:
 *      the connection is actually cancelled rather than merely ignored.
 *   2. Every request carries a SEQUENCE NUMBER, and a response whose sequence
 *      is not the latest issued is dropped on the floor. This is the part that
 *      saves correctness, and it is not redundant: `AbortController` only
 *      rejects a fetch that has not yet resolved, so a response that is already
 *      in flight through `await response.json()` when the abort lands still
 *      arrives at its `then` — and a `fetch` implementation, a service worker,
 *      or a test double is free to ignore the signal entirely.
 *
 * Without the second guard the classic bug is not hypothetical: type "ga",
 * which is slow because it matches half the platform, then finish typing
 * "games"; the narrow answer lands first, the broad one lands after it, and the
 * reader is looking at suggestions for a fragment they have already finished
 * typing past. Nothing on screen says so.
 *
 * A FRAGMENT BELOW THE MINIMUM IS NOT SENT. The endpoint answers it with an
 * empty list — see `SuggestQuery.isAnswerable()` — so the request cannot
 * succeed, and it is the request every visitor would make on the first
 * keystroke of every session.
 */

/** Milliseconds of quiet before a fragment is worth asking about. See above. */
export const SUGGEST_DEBOUNCE_MS = 200;

export type SuggestStatus = 'idle' | 'loading' | 'ready' | 'failed';

export interface SuggestionsState {
  readonly items: readonly Suggestion[];
  readonly status: SuggestStatus;
  /** The RFC 9457 problem the service answered with, for the caller to render. */
  readonly error: ApiError | null;
}

function wasAborted(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}

export function useSuggestions(fragment: string): SuggestionsState {
  const text = fragment.trim();
  const answerable = isAnswerable(text);

  const [items, setItems] = useState<readonly Suggestion[]>([]);
  const [status, setStatus] = useState<SuggestStatus>('idle');
  const [error, setError] = useState<ApiError | null>(null);

  /**
   * The sequence number of the most recent request ISSUED, not of the most
   * recent one that answered. A response is only allowed to write state when it
   * still holds this number — see guard 2 above.
   */
  const latest = useRef(0);

  useEffect(() => {
    if (!answerable) {
      /*
       * Nothing to ask, and nothing to show. The sequence is still bumped, so
       * that a request already in flight for a longer fragment cannot land
       * after the box was emptied and repopulate a list the reader closed.
       */
      latest.current += 1;
      setItems([]);
      setStatus('idle');
      setError(null);
      return;
    }

    const controller = new AbortController();
    const sequence = (latest.current += 1);

    /*
     * The status moves to `loading` only after the debounce, never on the
     * keystroke. A spinner that appears on every key and disappears again is a
     * flicker under the reader's hands, and it says the control is working when
     * nothing has been sent yet.
     */
    const timer = setTimeout(() => {
      setStatus('loading');

      void (async () => {
        try {
          const suggestions = await getSuggestions(text, { signal: controller.signal });
          if (sequence !== latest.current) return;

          setItems(suggestions);
          setStatus('ready');
          setError(null);
        } catch (cause) {
          if (sequence !== latest.current || wasAborted(cause)) return;

          /*
           * The items are cleared rather than kept. A stale list under a
           * failure is a list the reader will act on, and acting on it opens a
           * campaign that may no longer be what the fragment matches.
           */
          setItems([]);
          setStatus('failed');
          setError(cause instanceof ApiError ? cause : null);
        }
      })();
    }, SUGGEST_DEBOUNCE_MS);

    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [text, answerable]);

  return useMemo(() => ({ items, status, error }), [items, status, error]);
}
