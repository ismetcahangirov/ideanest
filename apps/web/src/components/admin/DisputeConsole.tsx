'use client';

import { useState } from 'react';
import {
  EmptyState,
  Field,
  InlineAlert,
  Pill,
  Select,
  Skeleton,
  SkeletonGroup,
  Tag,
  Textarea,
} from '@ideanest/ui';
import {
  DISPUTE_OUTCOMES,
  EVIDENCE_KINDS,
  addEvidence,
  hoursUntilDue,
  readDispute,
  readDisputeQueue,
  resolveDispute,
  submitEvidence,
  type Dispute,
  type DisputeState,
  type EvidenceKind,
} from '../../lib/admin/disputes';
import { consoleMessageFor, shortId } from '../../lib/admin/refusals';
import { formatMoney } from '../../lib/money';
import { fillNodes, fillPlaceholders } from '../../lib/i18n/placeholders';
import type { DisputeConsoleCopy } from '../../lib/i18n/admin/money-copy';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

/**
 * §4.11's AD-07: notification, evidence, outcome — issues #68 and #308.
 *
 * <h2>There is no button that opens a dispute</h2>
 *
 * A chargeback is raised by a card network and arrives through a provider webhook. This screen
 * answers cases; it cannot invent one, and the service has no endpoint that would let it.
 *
 * <h2>The deadline is the column everything is sorted by</h2>
 *
 * Everything else about a dispute could be reconstructed from the provider afterwards. A
 * deadline that has passed cannot, and losing by default is the expensive way to lose — so the
 * queue is ordered by it and each row says how long is left. A case with no deadline sorts
 * last rather than first: later is not sooner.
 *
 * <h2>Evidence is assembled and then submitted, in two steps</h2>
 *
 * Because that is how a representment is actually written — over days, by several people. What
 * `submit` does is mark the case answered and record who answered it. <strong>It does not send
 * the documents</strong>, and the screen says so: §9.3's provider interface has no evidence
 * submission on it, so the files go through the provider's own console until an adapter method
 * exists. A screen that implied otherwise would let a deadline pass while saying it was handled.
 */
export interface DisputeConsoleProps {
  readonly copy: DisputeConsoleCopy;
}

export function DisputeConsole({ copy }: DisputeConsoleProps) {
  const [page, setPage] = useState(0);
  const [openCase, setOpenCase] = useState<string | null>(null);

  const queue = useConsoleResource(
    (signal) => readDisputeQueue(page, signal),
    copy.subject,
    copy.refusals,
    [page],
  );

  if (queue.status === 'signed-out' || queue.status === 'forbidden') {
    return <ConsoleRefusal status={queue.status} capability={queue.capability} subject={copy.subject} copy={copy.refusals} />;
  }

  return (
    <div className="flex flex-col gap-8">
      <InlineAlert variant="info" title={copy.noticeTitle}>
        {copy.noticeBody}
      </InlineAlert>

      <section aria-labelledby="dispute-queue-heading">
        <h2 id="dispute-queue-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          {copy.heading}
          {queue.status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">
              {queue.data?.disputes.length ?? 0}
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

        {queue.status === 'ready' && queue.data !== null && queue.data.disputes.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title={copy.emptyTitle}
            description={copy.emptyBody}
          />
        )}

        {queue.status === 'ready' && queue.data !== null && queue.data.disputes.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-2">
            {queue.data.disputes.map((dispute) => (
              <li key={dispute.id}>
                <button
                  type="button"
                  onClick={() => setOpenCase(openCase === dispute.id ? null : dispute.id)}
                  aria-expanded={openCase === dispute.id}
                  className="w-full rounded-lg border border-white/8 bg-surface-1 p-4 text-left transition-colors duration-150 ease-in-out hover:border-white/16 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
                >
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <p className="text-sm text-white">
                      {formatMoney(dispute.amount)}
                      <span className="ml-2 text-white/48">
                        {/* The provider's name and the network's reason code are quoted into a
                            dispute, so both stay in the spelling the provider uses. */}
                        {fillPlaceholders(copy.providerAndReason, {
                          provider: dispute.provider,
                          code: dispute.reasonCode,
                        })}
                      </span>
                    </p>
                    <Deadline dispute={dispute} copy={copy} />
                  </div>
                  <p className="mt-2 text-xs text-white/40">
                    {fillNodes(copy.pledgeLine, {
                      pledge: <span className="font-mono">{shortId(dispute.pledgeId)}</span>,
                      fee: formatMoney(dispute.fee),
                    })}
                    <Tag>{copy.state[dispute.state]}</Tag>
                  </p>
                </button>

                {openCase === dispute.id && (
                  <CaseDetail disputeId={dispute.id} copy={copy} onChanged={queue.reload} />
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

/**
 * How long is left.
 *
 * Three renderings, because they mean different things: a case with no deadline is not urgent,
 * one that has expired cannot be answered, and one with hours left is the reason somebody
 * opened this screen.
 */
function Deadline({
  dispute,
  copy,
}: {
  readonly dispute: Dispute;
  readonly copy: DisputeConsoleCopy;
}) {
  const hours = hoursUntilDue(dispute);

  if (hours === null) {
    return <span className="text-xs text-white/40">{copy.noDeadline}</span>;
  }
  if (hours < 0) {
    return <Tag>{copy.deadlinePassed}</Tag>;
  }
  return (
    <span className="text-xs text-white/64">
      {hours < 48
        ? fillPlaceholders(copy.hoursLeft, { count: String(Math.floor(hours)) })
        : fillPlaceholders(copy.daysLeft, { count: String(Math.floor(hours / 24)) })}
    </span>
  );
}

/** One case, its evidence, and the two things staff can do to it. */
function CaseDetail({
  disputeId,
  copy,
  onChanged,
}: {
  readonly disputeId: string;
  readonly copy: DisputeConsoleCopy;
  readonly onChanged: () => void;
}) {
  const detail = useConsoleResource(
    (signal) => readDispute(disputeId, signal),
    copy.caseSubject,
    copy.refusals,
    [disputeId],
  );

  const [kind, setKind] = useState<EvidenceKind>('RECEIPT');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function act(work: () => Promise<unknown>): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await work();
      detail.reload();
      onChanged();
    } catch (cause) {
      setError(consoleMessageFor(cause, copy.caseSubject, copy.refusals));
    } finally {
      setBusy(false);
    }
  }

  if (detail.status === 'loading') {
    return (
      <SkeletonGroup label={copy.loadingCase} className="mt-2 rounded-lg border border-white/8 p-4">
        <Skeleton height="0.875rem" width="60%" />
      </SkeletonGroup>
    );
  }

  if (detail.status !== 'ready' || detail.data === null) {
    return (
      <InlineAlert variant="danger" title={copy.caseFailedTitle} className="mt-2">
        {detail.error ?? copy.tryAgainShort}
      </InlineAlert>
    );
  }

  const dispute = detail.data;

  return (
    <div className="mt-2 rounded-lg border border-white/8 bg-surface-1 p-4">
      <h3 className="text-sm font-medium text-white">{copy.evidenceHeading}</h3>

      {dispute.evidence.length === 0 ? (
        <p className="mt-2 text-xs text-white/48">{copy.noEvidence}</p>
      ) : (
        <ul className="mt-2 flex list-none flex-col gap-2">
          {dispute.evidence.map((piece) => (
            <li key={piece.id} className="rounded-md border border-white/8 p-3">
              <p className="text-xs text-white/80">{copy.evidenceKind[piece.kind]}</p>
              <p className="mt-1 text-xs text-white/64">{piece.description}</p>
              <p className="mt-1 text-xs text-white/40">
                {piece.submittedAt == null ? copy.notSent : copy.sent}
              </p>
            </li>
          ))}
        </ul>
      )}

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <Field label={copy.kindLabel} className="min-w-[220px]">
          <Select value={kind} onChange={(event) => setKind(event.target.value as EvidenceKind)}>
            {EVIDENCE_KINDS.map((option) => (
              <option key={option} value={option}>
                {copy.evidenceKind[option]}
              </option>
            ))}
          </Select>
        </Field>

        <Field label={copy.showsLabel} className="min-w-[280px] flex-1">
          <Textarea
            rows={2}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={5000}
          />
        </Field>

        <Pill
          variant="ghost"
          size="sm"
          className="mb-1"
          disabled={busy || description.trim() === ''}
          onClick={() =>
            void act(async () => {
              await addEvidence(dispute.id, kind, description.trim());
              setDescription('');
            })
          }
        >
          {copy.add}
        </Pill>
      </div>

      <div className="mt-4 flex flex-wrap gap-2 border-t border-white/8 pt-4">
        <Pill
          variant="outline"
          size="sm"
          disabled={busy}
          onClick={() => void act(() => submitEvidence(dispute.id))}
        >
          {copy.markAnswered}
        </Pill>

        {DISPUTE_OUTCOMES.map((outcome) => (
          <Pill
            key={outcome}
            variant="ghost"
            size="sm"
            disabled={busy}
            onClick={() => void act(() => resolveDispute(dispute.id, outcome as DisputeState))}
          >
            {copy.state[outcome]}
          </Pill>
        ))}
      </div>

      <p className="mt-3 text-xs text-white/40">{copy.outcomeNote}</p>

      {error && (
        <InlineAlert variant="danger" title={copy.failedTitle} className="mt-4">
          {error}
        </InlineAlert>
      )}
    </div>
  );
}
