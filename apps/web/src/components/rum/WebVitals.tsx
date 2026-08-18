'use client';

import { useCallback, useEffect, useRef } from 'react';
import { useReportWebVitals } from 'next/web-vitals';
import {
  attachFlushHandlers,
  browserEnvironment,
  createReporter,
  type ReportedMetric,
  type Reporter,
} from '../../lib/rum/reporter';

/**
 * The mount point. Subscribes to the browser's performance observers and hands
 * what they report to `lib/rum/reporter.ts`.
 *
 * <h2>It renders nothing, and that is the requirement rather than an economy</h2>
 *
 * There is no element, no text, no wrapper, and therefore nothing to animate,
 * nothing to give an accessible name to, and no surface for
 * `prefers-reduced-motion` to have an opinion about. A monitoring component that
 * rendered anything would be a component that could shift the layout it was
 * measuring — the cumulative layout shift number would then include the cost of
 * collecting it, which is the one bug this whole feature could not detect in
 * itself.
 *
 * <h2>Why a client component and not `instrumentation-client.ts`</h2>
 *
 * `instrumentation-client.ts` runs earlier, before hydration, which would be the
 * better place — but subscribing to web vitals from there means importing the
 * `web-vitals` library, and the only copy in this workspace is Next's private
 * `next/dist/compiled/web-vitals`. Importing a bundler's vendored copy by path
 * is a dependency on an implementation detail a patch release may move, and
 * adding the package outright is a new dependency. `useReportWebVitals` is the
 * documented API, ships with Next, and costs one client boundary that renders
 * nothing. `instrumentation-client.ts` is still used, for the one thing it is
 * uniquely able to do — see that file.
 *
 * <h2>Why the callback identity is pinned</h2>
 *
 * Next's documentation is explicit: "New functions passed to
 * `useReportWebVitals` are called with the available metrics up to that point."
 * The hook's effect depends on the function it was given, so a callback that
 * changed identity on every render would re-subscribe and re-deliver every
 * metric collected so far, every render — and the buffer would fill with copies
 * of one page load. `useCallback` with no dependencies is what stops that.
 */
export function WebVitals(): null {
  const reporter = useRef<Reporter | null>(null);
  const initialised = useRef(false);

  /**
   * Built on first use rather than during render.
   *
   * A `'use client'` component still renders on the server, where there is no
   * `window`, no `sessionStorage`, and nothing to measure. Everything here is
   * therefore deferred to the first call that can only happen in a browser.
   */
  const ensureReporter = useCallback((): Reporter | null => {
    if (initialised.current) return reporter.current;
    initialised.current = true;
    if (typeof window === 'undefined') {
      initialised.current = false;
      return null;
    }

    const environment = browserEnvironment(
      window,
      /*
       * A literal member access, because that is the only form the bundler
       * replaces with the value at build time. Reading it through a variable
       * would leave a `process.env` lookup in the browser bundle, where there is
       * no `process`.
       */
      process.env.NEXT_PUBLIC_IDEANEST_RUM_SAMPLE_RATE,
    );
    // Null when this visit is not sampled, or when the rate is zero. Every
    // metric is then dropped where it arrives, and no beacon is ever built.
    reporter.current = environment === null ? null : createReporter(environment);
    return reporter.current;
  }, []);

  const report = useCallback(
    (metric: ReportedMetric) => {
      ensureReporter()?.report(metric);
    },
    [ensureReporter],
  );

  useReportWebVitals(report);

  useEffect(() => {
    const current = ensureReporter();
    if (current === null) return;
    return attachFlushHandlers(window, () => void current.flush());
  }, [ensureReporter]);

  return null;
}
