'use client';

import { useState } from 'react';
import { InlineAlert, Pill, Skeleton, SkeletonGroup, Tag } from '@ideanest/ui';
import {
  FINDING_MEANINGS,
  FINDING_TITLES,
  readReconciliation,
  runReconciliation,
  type ReconciliationFinding,
  type ReconciliationReport,
} from '../../lib/admin/reconciliation';
import { consoleMessageFor, statusFor } from '../../lib/admin/refusals';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the reconciliation';

/**
 * §4.11's AD-05: do the platform's books add up? — issue #106.
 *
 * <h2>Three things this screen must never do</h2>
 *
 * <strong>It must not present "never run" as "balanced".</strong> A reconciliation that
 * silently stopped produces an empty finding list, which is exactly what a healthy platform
 * produces. The service answers `hasRun`, and the panel leads with it: a platform nobody has
 * checked is a finding of its own, and it is the one somebody is least likely to notice.
 *
 * <p><strong>It must not say "balanced" in green and leave it at that.</strong> CLAUDE.md
 * §2 — colour alone never carries meaning, and a screen whose entire content is a status is
 * where that rule is most expensive to break. Every state here is a sentence first.
 *
 * <p><strong>It must not offer to fix anything.</strong> `LedgerReconciliation` reports and
 * never repairs, because the correcting entry depends on which of a dozen things went wrong
 * and a job that guessed would turn a detectable problem into an undetectable one. There is
 * therefore no "resolve" control on this screen, and the absence is the design.
 *
 * <h2>The success colour is `--success`, and it is never lime</h2>
 *
 * CLAUDE.md §2: lime means "act now", not "this went well". Books that balance are the
 * opposite of an outstanding task, and lime here would tell a member of finance the reverse
 * of the truth on the one screen where that matters most. `InlineAlert`'s variants carry the
 * icon that goes with each, so the state reads without the colour at all.
 *
 * <h2>Motion: none</h2>
 *
 * `docs/motion-system.md` §5 gives the console no entry animation. Nothing here fades in,
 * and "Check again" swaps a label rather than animating.
 */
export function ReconciliationPanel() {
  const report = useConsoleResource<ReconciliationReport>(
    (signal) => readReconciliation(signal),
    SUBJECT,
    [],
  );

  /*
   * The run is held apart from `useConsoleResource` on purpose. That hook owns reads and
   * says so — "it does not own mutations… the interesting part of a write is always the
   * part that is specific to it" — and the specific part here is that a failed run must not
   * blank the report already on screen. Somebody looking at three findings and pressing
   * Check again should still be looking at three findings if the network drops.
   */
  const [running, setRunning] = useState(false);
  const [runFailure, setRunFailure] = useState<string | null>(null);

  async function check(): Promise<void> {
    if (running) return;
    setRunning(true);
    setRunFailure(null);
    try {
      report.set(await runReconciliation());
    } catch (cause) {
      const status = statusFor(cause);
      setRunFailure(
        status === 'failed'
          ? consoleMessageFor(cause, SUBJECT)
          : 'Your session no longer has permission to run a reconciliation.',
      );
    } finally {
      setRunning(false);
    }
  }

  if (report.status === 'signed-out' || report.status === 'forbidden') {
    return <ConsoleRefusal status={report.status} subject={SUBJECT} />;
  }

  if (report.status === 'loading' && report.data === null) {
    return (
      <SkeletonGroup label="Loading the reconciliation">
        <div className="rounded-lg border border-white/8 bg-surface-1 p-4">
          <Skeleton height="1rem" width="40%" />
          <Skeleton height="0.875rem" width="65%" className="mt-3" />
        </div>
      </SkeletonGroup>
    );
  }

  if (report.data === null) {
    return (
      <>
        <InlineAlert variant="danger" title="Something went wrong">
          {report.error ?? 'The reconciliation could not be read.'}
        </InlineAlert>
        <Pill variant="ghost" size="sm" className="mt-4" onClick={report.reload}>
          Try again
        </Pill>
      </>
    );
  }

  const found = report.data;

  return (
    <div className="flex flex-col gap-6">
      <Summary report={found} />

      {runFailure !== null && (
        <InlineAlert variant="danger" title="That check did not run">
          {runFailure}
        </InlineAlert>
      )}

      {found.findings.length > 0 && (
        <section aria-labelledby="findings-heading">
          <h2 id="findings-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
            What is wrong
          </h2>
          <ul className="mt-4 flex list-none flex-col gap-3">
            {found.findings.map((finding, index) => (
              <FindingCard key={`${finding.kind}:${finding.currency}:${index}`} finding={finding} />
            ))}
          </ul>
        </section>
      )}

      <div className="flex flex-wrap items-center gap-4">
        <Pill variant="ghost" size="sm" onClick={() => void check()} disabled={running}>
          {running ? 'Checking…' : 'Check again now'}
        </Pill>
        <p className="text-xs text-white/48">
          Two queries over the whole platform. It reads and writes nothing, so running it is
          safe at any time — and it is the only way to get an answer from the replica you are
          talking to.
        </p>
      </div>
    </div>
  );
}

/**
 * The headline, which is one of four states rather than two.
 *
 * <p>"Never run" and "balanced" are the pair that must not be collapsed; "checked but the
 * platform holds nothing" is worth its own sentence because a brand-new deployment reporting
 * zero positions is correct rather than broken.
 */
function Summary({ report }: { readonly report: ReconciliationReport }) {
  if (!report.hasRun) {
    return (
      <InlineAlert variant="warning" title="Nothing has been reconciled on this replica">
        The nightly pass keeps its result in the process that made it, so a replica that has
        restarted since 02:30 has nothing to report — which is not the same as balanced books.
        Run one now to get an answer.
      </InlineAlert>
    );
  }

  const when = new Date(report.runAt as string);

  if (!report.balanced) {
    return (
      <InlineAlert variant="danger" title="The books do not balance">
        {report.findings.length} finding{report.findings.length === 1 ? '' : 's'} across{' '}
        {report.accountsChecked} account position
        {report.accountsChecked === 1 ? '' : 's'}, checked {when.toISOString()}.{' '}
        <strong>Nothing has been corrected automatically</strong> — the entry that would fix
        this depends on which of a dozen things went wrong, so it is a decision rather than a
        sweep.
      </InlineAlert>
    );
  }

  return (
    <InlineAlert variant="success" title="The books balance">
      {report.accountsChecked} account position{report.accountsChecked === 1 ? '' : 's'} checked{' '}
      {when.toISOString()}, nothing out of place.
      {report.accountsChecked === 0 &&
        ' The platform holds no money in any account yet, which is balanced rather than unchecked.'}
    </InlineAlert>
  );
}

function FindingCard({ finding }: { readonly finding: ReconciliationFinding }) {
  return (
    <li className="rounded-lg border border-white/8 bg-surface-1 p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-sm text-white">{FINDING_TITLES[finding.kind]}</p>
        {/* The currency as a tag rather than in the sentence: a finding is about exactly
            one, and it is what somebody scans the list by. */}
        <Tag>{finding.currency}</Tag>
      </div>
      <p className="mt-2 break-words text-sm text-white/64">{finding.detail}</p>
      <p className="mt-2 max-w-[68ch] text-xs text-white/48">{FINDING_MEANINGS[finding.kind]}</p>
    </li>
  );
}
