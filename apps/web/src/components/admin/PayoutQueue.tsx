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
import { readMembership } from '../../lib/admin/staff';
import { consoleMessageFor, shortId } from '../../lib/admin/refusals';
import { formatMoney } from '../../lib/money';
import { fillNodes, fillPlaceholders } from '../../lib/i18n/placeholders';
import type { DirectoryNames } from '../../lib/admin/directory';
import type { PayoutQueueCopy } from '../../lib/i18n/admin/money-copy';
import { ConsoleRefusal } from './ConsoleRefusal';
import { EntityName } from './ConsoleIdentity';
import { useConsoleResource } from './useConsoleResource';
import { useDirectoryNames } from './useDirectoryNames';

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
 * <h2>The signature list names its signers — issue #402</h2>
 *
 * <p>Two-person approval exists to answer <em>which two people</em>, and this screen
 * rendered both of them as eight hexadecimal characters — a control nobody could audit by
 * reading it. The creator being paid was the same. Both are resolved through the console
 * directory now, with the fragment kept beside each name because that is what a support
 * ticket quotes.
 *
 * <h2>Every control matches the reader's own state — issue #405</h2>
 *
 * <p>The file used to offer <strong>Approve</strong> to somebody who had already signed it,
 * <strong>Withdraw mine</strong> to somebody who had not, and a <strong>Send</strong> that
 * was disabled at two signatures of two with nothing on the screen saying why. Each of those
 * is a control describing an action the reader cannot take, which is how a working screen
 * comes to read as a broken one.
 *
 * <p>So the reader's own membership is read once here and threaded into the file: the
 * signature list is compared against it, `Approve` is not offered to somebody already on
 * that list, `Withdraw mine` is offered only to somebody who is, and every control that is
 * disabled says beside itself what would enable it. <strong>None of this is a permission
 * check</strong> — the service decides all three, and `approve()` is idempotent, so what
 * changes here is only what the screen claims about itself.
 *
 * <h2>Motion: none</h2>
 *
 * The last screen on the platform that should feel eager.
 */
export interface PayoutQueueProps {
  readonly copy: PayoutQueueCopy;
}

export function PayoutQueue({ copy }: PayoutQueueProps) {
  const [page, setPage] = useState(0);
  const [openPayout, setOpenPayout] = useState<string | null>(null);
  const [projectId, setProjectId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const queue = useConsoleResource(
    (signal) => readPayoutQueue(page, signal),
    copy.subject,
    copy.refusals,
    [page],
  );

  /*
   * Who is reading, so the file can offer the control that matches their own signature
   * state (#405). Read here rather than inside each expanded file: `/v1/admin/me` refuses
   * nobody and answers the same thing every time, and one read per opened row would be a
   * request per click for a fact that does not change during a sitting.
   */
  const me = useConsoleResource(
    (signal) => readMembership(signal),
    copy.meSubject,
    copy.refusals,
    [],
  );

  /* The creators being paid, named — #402. */
  const names = useDirectoryNames(
    queue.data?.payouts.map((payout) => payout.creatorId) ?? [],
    queue.data?.payouts.map((payout) => payout.projectId) ?? [],
  );

  if (queue.status === 'signed-out' || queue.status === 'forbidden') {
    return <ConsoleRefusal status={queue.status} capability={queue.capability} subject={copy.subject} copy={copy.refusals} />;
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
      setNotice(
        fillPlaceholders(copy.calculatedNotice, {
          amount: formatMoney(payout.net),
          id: shortId(id),
          date: payout.payableAt.slice(0, 10),
        }),
      );
      setProjectId('');
      queue.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.subject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-10">
      <section aria-labelledby="calculate-heading">
        <h2 id="calculate-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.calculateHeading}
        </h2>
        <p className="mt-2 max-w-[62ch] text-sm text-white/64">{copy.calculateIntro}</p>

        <form onSubmit={(event) => void calculate(event)} className="mt-4 flex flex-wrap items-end gap-3">
          <Field label={copy.campaignLabel} hint={copy.campaignHint} className="min-w-[280px] flex-1">
            <TextInput
              value={projectId}
              onChange={(event) => setProjectId(event.target.value)}
              placeholder="00000000-0000-0000-0000-000000000000"
            />
          </Field>
          <Pill type="submit" variant="outline" size="sm" className="mb-1" disabled={busy}>
            {busy ? copy.working : copy.calculate}
          </Pill>
        </form>

        {notice && (
          <InlineAlert variant="success" title={copy.calculatedTitle} className="mt-4">
            {notice}
          </InlineAlert>
        )}
        {error && (
          <InlineAlert variant="danger" title={copy.failedTitle} className="mt-4">
            {error}
          </InlineAlert>
        )}
      </section>

      <section aria-labelledby="payout-queue-heading">
        <h2 id="payout-queue-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.queueHeading}
          {queue.status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">
              {queue.data?.payouts.length ?? 0}
            </span>
          )}
        </h2>

        {queue.status === 'loading' && (
          <SkeletonGroup label={copy.loadingList} className="mt-4">
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
            <InlineAlert variant="danger" title={copy.errorTitle} className="mt-4">
              {queue.error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={queue.reload}>
              {copy.tryAgain}
            </Pill>
          </>
        )}

        {queue.status === 'ready' && queue.data !== null && queue.data.payouts.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title={copy.emptyTitle}
            description={copy.emptyBody}
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
                        {fillNodes(copy.toCreator, {
                          id: (
                            <EntityName
                              id={payout.creatorId}
                              names={names}
                              kind="account"
                              copy={copy.identity}
                            />
                          ),
                        })}
                      </span>
                    </p>
                    <span className="flex items-center gap-2">
                      {!payout.payableNow && <Tag>{copy.held}</Tag>}
                      <Tag>{copy.state[payout.state]}</Tag>
                    </span>
                  </div>
                  <Breakdown payout={payout} copy={copy} />
                </button>

                {openPayout === payout.id && (
                  <PayoutDetail
                    payoutId={payout.id}
                    readerId={me.data?.accountId ?? null}
                    names={names}
                    copy={copy}
                    onChanged={queue.reload}
                  />
                )}
              </li>
            ))}
          </ul>
        )}

        {queue.status === 'ready' && (page > 0 || (queue.data?.hasMore ?? false)) && (
          <div className="mt-4 flex gap-2">
            <Pill variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((n) => n - 1)}>
              {copy.previous}
            </Pill>
            <Pill
              variant="ghost"
              size="sm"
              disabled={!(queue.data?.hasMore ?? false)}
              onClick={() => setPage((n) => n + 1)}
            >
              {copy.next}
            </Pill>
          </div>
        )}
      </section>
    </div>
  );
}

/** The five figures, in the order they subtract. */
function Breakdown({
  payout,
  copy,
}: {
  readonly payout: Payout;
  readonly copy: PayoutQueueCopy;
}) {
  return (
    <StatRow className="mt-3 flex flex-wrap gap-x-8 gap-y-3">
      {/*
        The minus signs are the component's and not the catalogue's: they are arithmetic
        notation rather than words, and a translation that lost one would turn a deduction
        into an addition on the screen where that costs the most.
      */}
      <StatBlock size="md" label={copy.collected} value={formatMoney(payout.gross)} />
      <StatBlock size="md" label={copy.platformFee} value={`− ${formatMoney(payout.platformFee)}`} />
      <StatBlock size="md" label={copy.processing} value={`− ${formatMoney(payout.processingFee)}`} />
      <StatBlock size="md" label={copy.refunded} value={`− ${formatMoney(payout.refunded)}`} />
      <StatBlock size="md" label={copy.net} value={formatMoney(payout.net)} />
    </StatRow>
  );
}

/**
 * One payout, who has signed it, and the three things that can be done to it.
 *
 * <p>`readerId` is null while `/v1/admin/me` is still loading, and the file is honest about
 * that rather than guessing: with no answer yet it offers `Approve` and withholds
 * `Withdraw mine`, which is the state of a payout nobody has signed. Guessing the other way
 * would offer to withdraw a signature that may not exist.
 */
function PayoutDetail({
  payoutId,
  readerId,
  names,
  copy,
  onChanged,
}: {
  readonly payoutId: string;
  readonly readerId: string | null;
  readonly names: DirectoryNames;
  readonly copy: PayoutQueueCopy;
  readonly onChanged: () => void;
}) {
  const file = useConsoleResource(
    (signal) => readPayout(payoutId, signal),
    copy.payoutSubject,
    copy.refusals,
    [payoutId],
  );

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
      setError(consoleMessageFor(cause, copy.payoutSubject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  if (file.status === 'loading') {
    return (
      <SkeletonGroup label={copy.loadingPayout} className="mt-2 rounded-lg border border-white/8 p-4">
        <Skeleton height="0.875rem" width="60%" />
      </SkeletonGroup>
    );
  }

  if (file.status !== 'ready' || file.data === null) {
    return (
      <InlineAlert variant="danger" title={copy.payoutFailedTitle} className="mt-2">
        {file.error ?? copy.tryAgainShort}
      </InlineAlert>
    );
  }

  const { payout, approvals, stillNeeded } = file.data;

  // The three facts every control below turns on. `approve()` is idempotent server-side and
  // the service decides all of this anyway; what these change is what the screen claims.
  const iHaveSigned = readerId !== null && approvals.some((one) => one.approverId === readerId);
  const canApprove = payout.payableNow && !iHaveSigned;
  const destinationGiven = destination.trim() !== '';

  return (
    <div className="mt-2 rounded-lg border border-white/8 bg-surface-1 p-4">
      <h3 className="text-sm font-medium text-white">
        {copy.signatures}
        <span className="ml-2 text-xs font-normal text-white/48">
          {fillPlaceholders(copy.signatureCount, {
            have: String(approvals.length),
            need: String(payout.approvalsRequired),
          })}
        </span>
      </h3>

      {approvals.length === 0 ? (
        <p className="mt-2 text-xs text-white/48">
          {copy.noSignatures}
          {payout.approvalsRequired > 1 ? ` ${copy.needsTwo}` : ''}
        </p>
      ) : (
        <ul className="mt-2 flex list-none flex-col gap-1">
          {approvals.map((approval) => (
            <li key={approval.approverId} className="text-xs text-white/64">
              {fillNodes(copy.approvalLine, {
                approver: (
                  <EntityName
                    id={approval.approverId}
                    names={names}
                    kind="account"
                    copy={copy.identity}
                  />
                ),
                date: approval.approvedAt.slice(0, 10),
              })}
              {approval.note ? ` — ${approval.note}` : ''}
              {/*
                Marked rather than left for the reader to compare identifiers. "One of
                these two is you" is the fact the controls below turn on, and a reader who
                cannot see which is being asked to trust the buttons.
              */}
              {readerId === approval.approverId ? ` — ${copy.thisIsYou}` : ''}
            </li>
          ))}
        </ul>
      )}

      {!payout.payableNow && (
        <InlineAlert variant="info" title={copy.stillHeldTitle} className="mt-4">
          {fillPlaceholders(copy.stillHeldBody, { date: payout.payableAt.slice(0, 10) })}
        </InlineAlert>
      )}

      <div className="mt-4 flex flex-wrap items-end gap-3 border-t border-white/8 pt-4">
        <Field label={copy.noteLabel} hint={copy.noteHint} className="min-w-[240px] flex-1">
          <TextInput value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>

        <Pill
          variant="outline"
          size="sm"
          className="mb-1"
          disabled={busy || !canApprove}
          onClick={() =>
            void act(async () => {
              await approvePayout(payoutId, note.trim() === '' ? null : note.trim());
              setNote('');
            })
          }
        >
          {copy.approve}
        </Pill>

        {/*
          Every disabled control says what would enable it — #405. The hold is checked
          first because it is the one a reader can wait out; having signed already is
          permanent for them.
        */}
        {!canApprove && (
          <p className="mb-2 text-xs text-white/48">
            {!payout.payableNow
              ? fillPlaceholders(copy.awaitingHold, { date: payout.payableAt.slice(0, 10) })
              : copy.youHaveSigned}
          </p>
        )}

        {/*
          Offered only to somebody who has something to withdraw. At one signature of two
          with only a colleague's name on the file, this used to offer to take back a
          signature the reader had never given.
        */}
        {iHaveSigned && (
          <Pill
            variant="ghost"
            size="sm"
            className="mb-1"
            disabled={busy}
            onClick={() => void act(() => withdrawApproval(payoutId))}
          >
            {copy.withdrawMine}
          </Pill>
        )}
      </div>

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <Field
          label={copy.destinationLabel}
          hint={copy.destinationHint}
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
          disabled={busy || stillNeeded > 0 || !destinationGiven}
          onClick={() => void act(() => sendPayout(payoutId, destination.trim()))}
        >
          {stillNeeded > 0
            ? fillPlaceholders(copy.needsMore, { count: String(stillNeeded) })
            : copy.send}
        </Pill>

        {/*
          #405: at two signatures of two the label changed from "one more signature
          needed" to "Send" and the control then simply did not work. The reason was real
          and stated nowhere on the row — a disabled control with no reason reads as a bug,
          and this is the row where somebody is trying to pay a creator.
        */}
        {stillNeeded === 0 && !destinationGiven && (
          <p className="mb-2 text-xs text-white/48">{copy.destinationNeeded}</p>
        )}

        <Pill
          variant="ghost"
          size="sm"
          className="mb-1"
          disabled={busy}
          onClick={() => void act(() => cancelPayout(payoutId))}
        >
          {copy.cancel}
        </Pill>
      </div>

      {error && (
        <InlineAlert variant="danger" title={copy.failedTitle} className="mt-4">
          {error}
        </InlineAlert>
      )}
    </div>
  );
}
