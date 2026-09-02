'use client';

import { useEffect, useMemo, useState } from 'react';
import {
  NO_NAMES,
  identifiersIn,
  lookUpNames,
  namesFrom,
  type DirectoryNames,
} from '../../lib/admin/directory';
import { wasAborted } from '../../lib/admin/refusals';

/**
 * Names for the identifiers a console screen is holding — issue #402.
 *
 * <h2>A second read, and it is deliberately not part of the first</h2>
 *
 * The screen's own resource is what it came for; this is what turns the identifiers in it
 * into people and campaigns. They are kept apart for the reason `ReportDetail` keeps its
 * audit trail apart from its report: <strong>a failure here must cost the names and never
 * the screen.</strong> A payout queue whose directory lookup timed out is a payout queue
 * with eight-character identifiers on it, which is exactly what the console did before this
 * existed and is still a working screen. One that had replaced itself with "something went
 * wrong" would not be.
 *
 * <p>So there is no status and no error in what this returns. There is a map, and it is
 * empty until the lookup answers.
 *
 * <h2>It reads what it is given and never scrapes</h2>
 *
 * The caller passes the identifiers because only the caller knows which fields on its rows
 * are people and which are campaigns — `grantedBy` is an account and `entityId` on an audit
 * row is whatever `entityType` says it is. A hook that guessed would ask about the wrong
 * things and quietly render nothing for them.
 *
 * <h2>Why the dependency is the joined list rather than the array</h2>
 *
 * Every caller builds its arrays inside the render from the rows it is holding, so they are
 * new arrays on every pass and a dependency on them would re-request on every render for
 * ever. Joining them is a string that changes exactly when the set of identifiers does,
 * which is the question the effect actually has.
 */
export function useDirectoryNames(
  accountIds: readonly string[],
  projectIds: readonly string[],
): DirectoryNames {
  const [names, setNames] = useState<DirectoryNames>(NO_NAMES);

  const accountKey = accountIds.join(',');
  const projectKey = projectIds.join(',');

  const accounts = useMemo(
    () => identifiersIn(accountIds),
    // The joined list rather than the array: see the docblock.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [accountKey],
  );
  const projects = useMemo(
    () => identifiersIn(projectIds),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [projectKey],
  );

  useEffect(() => {
    if (accounts.length === 0 && projects.length === 0) {
      setNames(NO_NAMES);
      return;
    }

    const controller = new AbortController();

    async function resolve(): Promise<void> {
      try {
        const directory = await lookUpNames(accounts, projects, controller.signal);
        if (controller.signal.aborted) return;
        setNames(namesFrom(directory));
      } catch (cause) {
        if (controller.signal.aborted || wasAborted(cause)) return;
        /*
         * Swallowed on purpose, and this is the only place in the console where that is
         * the right thing to do. The screen has already rendered every fact it was asked
         * to show; the names are an improvement on those rows, and an improvement that
         * failed leaves the rows. There is nothing for a reader to act on and nothing for
         * them to retry, so an alert here would be a red box about a screen that is
         * working.
         */
        setNames(NO_NAMES);
      }
    }

    void resolve();
    return () => controller.abort();
    // The joined lists rather than the arrays: see the docblock. `accounts` and `projects`
    // are already memoised on exactly that.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accounts, projects]);

  return names;
}
