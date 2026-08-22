'use client';

import { useEffect } from 'react';
import { FailureState } from '../components/shell/FailureState';
import { MinimalShell } from '../components/shell/MinimalShell';

/**
 * The error boundary of last resort below the root layout — §4.13 WS-09, issue #263.
 *
 * <h2>`'use client'` is required, not chosen</h2>
 *
 * An `error.tsx` is always a Client Component: it is the boundary React resets, and `reset` is
 * a function it is handed. It is also at the root, so — like `not-found.tsx` beside it — its
 * imports reach every route's first load, which is why the frame is `MinimalShell` and not
 * `SiteShell`. `app/(site)/error.tsx` is the one that keeps the full header, on the routes
 * that already carry it.
 *
 * <h2>What it must not print</h2>
 *
 * **Not `error.message`.** In a production build Next replaces the message with a generic one
 * and a digest, precisely so that a server-side stack does not reach a browser; in development
 * it is the real message, which means a component that printed it would show one thing in
 * review and another in production. Either way the string is written for whoever reads the
 * logs, not for the person on the page.
 *
 * **The digest, on the other hand, is exactly what to print.** It is the identifier that ties
 * this screen to a line in the server log, so somebody reporting the problem can quote it and
 * be believed. It is opaque and carries nothing about the request.
 *
 * <h2>Retry first, navigate second</h2>
 *
 * `reset()` re-renders the segment that threw, which is genuinely the right first move for the
 * failure this boundary catches most: a read that timed out while the service was restarting.
 * The home page is the second offer rather than the first, because taking somebody away from
 * the page they wanted should not be the default answer to a transient fault.
 *
 * `console.error` in an effect rather than during render — an effect runs once per mount,
 * where render may run several times, and a boundary that logged on every render would
 * multiply one fault in whatever collects browser errors.
 */
export default function RouteError({
  error,
  reset,
}: {
  readonly error: Error & { readonly digest?: string };
  readonly reset: () => void;
}) {
  useEffect(() => {
    console.error('A route failed to render.', error);
  }, [error]);

  return (
    <MinimalShell>
      <FailureState
        title="Something went wrong on our side"
        description={
          <>
            <p>
              This page could not be rendered. It is usually temporary — trying again is worth
              one press before anything else.
            </p>
            {error.digest !== undefined && (
              <p className="mt-4 text-sm text-white/40">
                Reference{' '}
                <span className="font-mono tabular-nums text-white/64">{error.digest}</span>.
                Quoting it lets us find the exact failure in the log.
              </p>
            )}
          </>
        }
        action={
          <button
            type="button"
            onClick={reset}
            className="inline-flex h-12 items-center rounded-full bg-white px-6 text-base font-medium text-on-white transition-colors duration-150 ease-in-out hover:bg-[var(--white-muted)]"
          >
            Try again
          </button>
        }
      />
    </MinimalShell>
  );
}
