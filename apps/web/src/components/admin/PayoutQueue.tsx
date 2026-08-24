'use client';

import { useState } from 'react';
import {
  EmptyState,
  Field,
  InlineAlert,
  Pill,
  Skeleton,
  SkeletonGroup,
  StatBlock,
  StatRow,
  Tag,
  TextInput,
} from '@ideanest/ui';
import {
  approvePayout,
  calculatePayout,
  cancelPayout,
  readPayout,
  readPayoutQueue,
  sendPayout,
  withdrawApproval,
  type Payout,
} from '../../lib/admin/payouts';
import { consoleMessageFor, shortId } from '../../lib/admin/refusals';
import { formatMoney } from '../../lib/money';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the payout queue';

/**
 * §4.11's AD-05: the payout queue and its approvals — issues #69 and #306.
 *
 * <h2>Every figure is shown, and that is the whole design of the screen</h2>
 *
 * This is where somebody signs off money leaving the platform. A single net figure with a
 * note saying "fees deducted" is not something anybody can check, so all five numbers are on
 * the row and they add up in front of the reader: what came in, what the platform took, what
 * the processor took, what went back as refunds, and what is left.
 *
 * <h2>The hold is visible rather than hidden</h2>
 *
 * A payout is calculated as soon as a campaign closes and becomes payable when §9's hold
 * expires. Both are in the queue, and each row says which — hiding the held ones would make a
 * creator's "when will I be paid" unanswerable for a fortnight, and showing them as awaiting
 * a signature would be asking for one that cannot be given.
 *
 * <h2>`payableNow` comes from the service and is not computed here</h2>
 *
 * A client comparing `payableAt` to its own clock would show a payout as approvable a few
 * seconds early, and the service would then refuse a button that looked enabled. The same goes
 * for `stillNeeded`: the browser cannot see that two approval rows are two different accounts,
 * which is the rule dual approval actually turns on.
 *
 * <h2>Motion: none</h2>
 *
 * The last screen on the platform that should feel eager.
 */
export function PayoutQueue() {
  const [page, setPage] = useState(0);
  const [openPayout, setOpenPayout] = useState<string | null>(null);
  const [projectId, setProjectId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const queue = useConsoleResource((signal) => readPayoutQueue(page, signal), SUBJECT, [page]);

  if (queue.status === 'signed-out' || queue.status === 'forbidden') {
    return <ConsoleRefusal status={queue.status} subject={SUBJECT} />;
  }

  async function calculate(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    const id = projectId.trim();
    if (id === '') return;

    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const payout = await calculatePayout(id);
      setNotice(`Calculated ${formatMoney(payout.net)} for ${shortId(id)}. The hold runs until ${payout.payableAt.slice(0, 10)}.`);
      setProjectId('');
      queue.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-10">
      <section aria-labelledby="calculate-heading">
        <h2 id="calculate-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Work out what a campaign owes
        </h2>
        <p className="mt-2 max-w-[62ch] text-sm text-white/64">
          The figures are frozen at this moment and never recomputed — a payout two people
          approved has to be the payout that is sent. If the collections or refunds move
          afterwards, the send is refused and a fresh calculation replaces it.
        </p>

        <form onSubmit={(event) => void calculate(event)} className="mt-4 flex flex-wrap items-end gap-3">
          <Field label="Campaign" hint="The whole identifier." className="min-w-[280px] flex-1">
            <TextInput
              value={projectId}
              onChange={(event) => setProjectId(event.target.value)}
              placeholder="00000000-0000-0000-0000-000000000000"
            />
          </Field>
          <Pill type="submit" variant="outline" size="sm" className="mb-1" disabled={busy}>
            {busy ? 'Working' : 'Calculate'}
          </Pill>
        </form>

        {notice && (
          <InlineAlert variant="success" title="Calculated" className="mt-4">
            {notice}
          </InlineAlert>
        )}
        {error && (
          <InlineAlert variant="danger" title="That did not work" className="mt-4">
            {error}
          </InlineAlert>
        )}
      </section>

      <section aria-labelledby="payout-queue-heading">
        <h2 id="payout-queue-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          In flight
          {queue.status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">
              {queue.data?.payouts.length ?? 0}
            </span>
          )}
        </h2>

        {queue.status === 'loading' && (
          <SkeletonGroup label="Loading the payout queue" className="mt-4">
            <div className="space-y-3">
              {[0, 1].map((row) => (
                <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                  <Skeleton height="1rem" width="45%" />
                  <Skeleton height="0.875rem" width="70%" className="mt-3" />
                </div>
              ))}
            </div>
          </SkeletonGroup>
        )}

        {queue.status === 'failed' && (
          <>
            <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
              {queue.error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={queue.reload}>
              Try again
            </Pill>
          </>
        )}

        {queue.status === 'ready' && queue.data !== null && queue.data.payouts.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title="Nothing is waiting to be paid"
            description="No campaign has a payout in flight. Work one out above once a campaign has closed and collected."
          />
        )}

        {queue.status === 'ready' && queue.data !== null && queue.data.payouts.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-3">
            {queue.data.payouts.map((payout) => (
              <li key={payout.id}>
                <button
                  type="button"
                  onClick={() => setOpenPayout(openPayout === payout.id ? null : payout.id)}
                  aria-expanded={openPayout === payout.id}
                  className="w-full rounded-lg border border-white/8 bg-surface-1 p-4 text-left transition-colors duration-150 ease-in-out hover:border-white/16 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                >
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <p className="text-sm text-white">
                      {formatMoney(payout.net)}
                      <span className="ml-2 text-white/48">
                        to <span className="font-mono">{shortId(payout.creatorId)}</span>
                      </span>
                    </p>
                    <span className="flex items-center gap-2">
                      {!payout.payableNow && <Tag>Held</Tag>}
                      <Tag>{payout.state}</Tag>
                    </span>
                  </div>
                  <Breakdown payout={payout} />
                </button>

                {openPayout === payout.id && <PayoutDetail payoutId={payout.id} onChanged={queue.reload} />}
              </li>
            ))}
          </ul>
        )}

        {queue.status === 'ready' && (page > 0 || (queue.data?.hasMore ?? false)) && (
          <div className="mt-4 flex gap-2">
            <Pill variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((n) => n - 1)}>
              Previous
            </Pill>
            <Pill
              variant="ghost"
              size="sm"
              disabled={!(queue.data?.hasMore ?? false)}
              onClick={() => setPage((n) => n + 1)}
            >
              Next
            </Pill>
          </div>
        )}
      </section>
    </div>
  );
}

/** The five figures, in the order they subtract. */
function Breakdown({ payout }: { readonly payout: Payout }) {
  return (
    <StatRow className="mt-3 flex flex-wrap gap-x-8 gap-y-3">
      <StatBlock size="md" label="Collected" value={formatMoney(payout.gross)} />
      <StatBlock size="md" label="Platform fee" value={`− ${formatMoney(payout.platformFee)}`} />
      <StatBlock size="md" label="Processing" value={`− ${formatMoney(payout.processingFee)}`} />
      <StatBlock size="md" label="Refunded" value={`− ${formatMoney(payout.refunded)}`} />
      <StatBlock size="md" label="Net" value={formatMoney(payout.net)} />
    </StatRow>
  );
}

/** One payout, who has signed it, and the three things that can be done to it. */
function PayoutDetail({
  payoutId,
  onChanged,
}: {
  readonly payoutId: string;
  readonly onChanged: () => void;
}) {
  const file = useConsoleResource((signal) => readPayout(payoutId, signal), 'this payout', [payoutId]);

  const [note, setNote] = useState('');
  const [destination, setDestination] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function act(work: () => Promise<unknown>): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await work();
      file.reload();
      onChanged();
    } catch (cause) {
      setError(consoleMessageFor(cause, 'this payout'));
    } finally {
      setBusy(false);
    }
  }

  if (file.status === 'loading') {
    return (
      <SkeletonGroup label="Loading the payout" className="mt-2 rounded-lg border border-white/8 p-4">
        <Skeleton height="0.875rem" width="60%" />
      </SkeletonGroup>
    );
  }

  if (file.status !== 'ready' || file.data === null) {
    return (
      <InlineAlert variant="danger" title="The payout could not be read" className="mt-2">
        {file.error ?? 'Try again.'}
      </InlineAlert>
    );
  }

  const { payout, approvals, stillNeeded } = file.data;

  return (
    <div className="mt-2 rounded-lg border border-white/8 bg-surface-1 p-4">
      <h3 className="text-sm font-medium text-white">
        Signatures
        <span className="ml-2 text-xs font-normal text-white/48">
          {approvals.length} of {payout.approvalsRequired}
        </span>
      </h3>

      {approvals.length === 0 ? (
        <p className="mt-2 text-xs text-white/48">
          Nobody has signed this off yet.
          {payout.approvalsRequired > 1
            ? ' It is above the threshold, so it needs two different people.'
            : ''}
        </p>
      ) : (
        <ul className="mt-2 flex list-none flex-col gap-1">
          {approvals.map((approval) => (
            <li key={approval.approverId} className="text-xs text-white/64">
              <span className="font-mono">{shortId(approval.approverId)}</span> on{' '}
              {approval.approvedAt.slice(0, 10)}
              {approval.note ? ` — ${approval.note}` : ''}
            </li>
          ))}
        </ul>
      )}

      {!payout.payableNow && (
        <InlineAlert variant="info" title="Still held" className="mt-4">
          §9&apos;s hold runs until {payout.payableAt.slice(0, 10)}. It exists so that refunds and
          chargebacks land before the money leaves, so a signature given now would be a signature
          on a figure nobody can yet know is right.
        </InlineAlert>
      )}

      <div className="mt-4 flex flex-wrap items-end gap-3 border-t border-white/8 pt-4">
        <Field label="Note" hint="Why, for the second approver." className="min-w-[240px] flex-1">
          <TextInput value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>

        <Pill
          variant="outline"
          size="sm"
          className="mb-1"
          disabled={busy || !payout.payableNow}
          onClick={() =>
            void act(async () => {
              await approvePayout(payoutId, note.trim() === '' ? null : note.trim());
              setNote('');
            })
          }
        >
          Approve
        </Pill>

        <Pill
          variant="ghost"
          size="sm"
          className="mb-1"
          disabled={busy}
          onClick={() => void act(() => withdrawApproval(payoutId))}
        >
          Withdraw mine
        </Pill>
      </div>

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <Field
          label="Destination"
          hint="Typed per send — §9's payout-destination schema is not built."
          className="min-w-[280px] flex-1"
        >
          <TextInput
            value={destination}
            onChange={(event) => setDestination(event.target.value)}
            autoComplete="off"
          />
        </Field>

        <Pill
          variant="outline"
          size="sm"
          className="mb-1"
          disabled={busy || stillNeeded > 0 || destination.trim() === ''}
          onClick={() => void act(() => sendPayout(payoutId, destination.trim()))}
        >
          {stillNeeded > 0 ? `Needs ${stillNeeded} more` : 'Send'}
        </Pill>

        <Pill
          variant="ghost"
          size="sm"
          className="mb-1"
          disabled={busy}
          onClick={() => void act(() => cancelPayout(payoutId))}
        >
          Cancel
        </Pill>
      </div>

      {error && (
        <InlineAlert variant="danger" title="That did not work" className="mt-4">
          {error}
        </InlineAlert>
      )}
    </div>
  );
}
