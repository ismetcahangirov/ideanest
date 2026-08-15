'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../../lib/api/problem';

/**
 * Autosave, per the epic contract §6: debounced, one request in flight at a
 * time, a visible state, and a retry that never loses what was typed.
 *
 * The last clause is the hard one, and it is why the pending patch is only
 * cleared by a SUCCESSFUL response. A failed save that dropped its body would
 * leave the creator looking at a title the service has never heard of, and they
 * would only find out when the campaign went live with the old one.
 *
 * ONE REQUEST AT A TIME, because these are merge patches and the server applies
 * them in the order it receives them. Two overlapping saves can be answered out
 * of order, and the older body wins — which on a title field means a keystroke
 * travelling backwards in time. Anything typed while a request is in flight
 * merges into the next one instead.
 *
 * Written as a hook rather than as part of the basics form because #34, #35 and
 * #39 autosave the same way through the same endpoint. A second implementation
 * of this is a second set of these bugs.
 */

export type SaveState = 'idle' | 'saving' | 'saved' | 'failed';

export interface SaveFailure {
  /** A sentence to show the creator. The server's, when the server wrote one. */
  message: string;
  /** Field name to message, from a validation failure's problem details. */
  fieldErrors: Readonly<Record<string, string>>;
  status: number | null;
  /** The machine-readable reason, e.g. `PROJECT_TRANSITION_NOT_ALLOWED`. */
  code: string | null;
}

export interface Autosave<P> {
  state: SaveState;
  failure: SaveFailure | null;
  /** True while something typed has not yet been acknowledged by the server. */
  pending: boolean;
  /** Queue a partial change. Debounced, and merged with anything still waiting. */
  save: (patch: P) => void;
  /** Send now — on blur, or when the creator is about to leave. */
  flush: () => void;
  /** Send the same pending patch again, after a failure. */
  retry: () => void;
}

export interface AutosaveOptions<P, R> {
  send: (patch: P) => Promise<R>;
  /** The server's answer, which is the authority on what the project now is. */
  onSaved?: (result: R) => void;
  /** How long the creator has to stop typing before a request goes out. */
  delayMs?: number;
}

/**
 * Turns a failure into something a creator can act on.
 *
 * The service's `detail` is preferred wherever it exists, because a problem
 * detail written by the endpoint knows which of its rules was broken and this
 * function cannot. The wording below is for the cases where there is no body to
 * read, or where the status means something the creator has to be told plainly.
 */
export function describeFailure(cause: unknown): SaveFailure {
  if (cause instanceof ApiError) {
    const detail = cause.problem?.detail ?? cause.problem?.title ?? null;
    const fieldErrors = cause.problem?.errors ?? {};
    const code = cause.problem?.code ?? null;

    const message =
      detail ??
      (cause.status === 401
        ? 'You have been signed out. Sign in again — nothing you typed has been lost.'
        : cause.status === 403
          ? 'You are not allowed to edit this project.'
          : cause.status === 404
            ? 'This project no longer exists.'
            : cause.status === 409
              ? 'The project has changed since this page was opened. Reload it to see the current version.'
              : cause.status === 422 || cause.status === 400
                ? 'The service rejected the change. Check the fields marked below.'
                : 'The change could not be saved. Try again.');

    return { message, fieldErrors, status: cause.status, code };
  }

  return {
    message: 'The service could not be reached, so nothing was saved. Try again.',
    fieldErrors: {},
    status: null,
    code: null,
  };
}

export function useAutosave<P extends object, R>({
  send,
  onSaved,
  delayMs = 800,
}: AutosaveOptions<P, R>): Autosave<P> {
  const [state, setState] = useState<SaveState>('idle');
  const [failure, setFailure] = useState<SaveFailure | null>(null);
  const [pending, setPending] = useState(false);

  /** Merged, not yet sent. Cleared only by a response that succeeded. */
  const queued = useRef<Partial<P> | null>(null);
  const inFlight = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // The callbacks are read through refs so that a caller passing an inline
  // arrow function — which is every caller — does not reset the debounce on
  // every render.
  const sendRef = useRef(send);
  const onSavedRef = useRef(onSaved);
  useEffect(() => {
    sendRef.current = send;
    onSavedRef.current = onSaved;
  }, [send, onSaved]);

  const run = useCallback((): void => {
    if (inFlight.current) return;

    const patch = queued.current;
    if (patch === null) return;

    queued.current = null;
    inFlight.current = true;
    setState('saving');

    void sendRef.current(patch as P).then(
      (result) => {
        inFlight.current = false;
        setFailure(null);
        onSavedRef.current?.(result);

        // Something arrived while this was in the air. Send it rather than
        // claiming everything is saved.
        if (queued.current !== null) {
          setState('saving');
          run();
          return;
        }

        setPending(false);
        setState('saved');
      },
      (cause: unknown) => {
        inFlight.current = false;

        /*
         * The body goes back on the queue, with anything newer winning. This is
         * what makes the retry lossless: the creator's text is still here, and
         * it is still here after the second failure too.
         */
        queued.current = { ...patch, ...(queued.current ?? {}) };
        setFailure(describeFailure(cause));
        setPending(true);
        setState('failed');
      },
    );
  }, []);

  const schedule = useCallback((): void => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      timer.current = null;
      run();
    }, delayMs);
  }, [delayMs, run]);

  const save = useCallback(
    (patch: P): void => {
      queued.current = { ...(queued.current ?? {}), ...patch };
      setPending(true);
      /*
       * "Saving" the moment a key is pressed, before the debounce has elapsed.
       * The alternative is to leave "Saved" on screen while unsent text sits in
       * the queue, and that is the one thing an autosave indicator must never
       * say — a creator who reads it and closes the tab loses the difference.
       */
      setState('saving');
      schedule();
    },
    [schedule],
  );

  const flush = useCallback((): void => {
    if (timer.current !== null) {
      clearTimeout(timer.current);
      timer.current = null;
    }
    run();
  }, [run]);

  const retry = useCallback((): void => {
    if (queued.current === null) return;
    setFailure(null);
    flush();
  }, [flush]);

  useEffect(() => {
    return () => {
      if (timer.current !== null) clearTimeout(timer.current);

      /*
       * Leaving the tab must not discard the last few seconds of typing. The
       * request is fired without being awaited — the component is going away and
       * has nowhere to put the answer — and it is a merge patch, so arriving
       * twice is the same as arriving once.
       */
      if (queued.current !== null && !inFlight.current) {
        const patch = queued.current;
        queued.current = null;
        void sendRef.current(patch as P).catch(() => {
          // Nothing is left to report it to. The next load reads the server's
          // version, which is the truth either way.
        });
      }
    };
  }, []);

  return { state, failure, pending, save, flush, retry };
}
