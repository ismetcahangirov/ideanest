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
  EVIDENCE_KIND_LABELS,
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
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the chargeback queue';

const KINDS = Object.keys(EVIDENCE_KIND_LABELS) as EvidenceKind[];

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
export function DisputeConsole() {
  const [page, setPage] = useState(0);
  const [openCase, setOpenCase] = useState<string | null>(null);

  const queue = useConsoleResource((signal) => readDisputeQueue(page, signal), SUBJECT, [page]);

  if (queue.status === 'signed-out' || queue.status === 'forbidden') {
    return <ConsoleRefusal status={queue.status} subject={SUBJECT} />;
  }

  return (
    <div className="flex flex-col gap-8">
      <InlineAlert variant="info" title="Evidence is sent through the provider">
        Submitting here records that the case was answered and by whom. §9.3&apos;s provider
        interface has no evidence upload, so the documents themselves still go through the
        provider&apos;s own console — this screen is the platform&apos;s record of the case, not
        the channel to the network.
      </InlineAlert>

      <section aria-labelledby="dispute-queue-heading">
        <h2 id="dispute-queue-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Open cases
          {queue.status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">
              {queue.data?.disputes.length ?? 0}
            </span>
          )}
        </h2>

        {queue.status === 'loading' && (
          <SkeletonGroup label="Loading the chargeback queue" className="mt-4">
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

        {queue.status === 'ready' && queue.data !== null && queue.data.disputes.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title="No open chargebacks"
            description="Nothing is waiting for an answer. Cases arrive here from a provider webhook, so an empty queue means no network has disputed a charge."
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
                        {dispute.provider} · {dispute.reasonCode}
                      </span>
                    </p>
                    <Deadline dispute={dispute} />
                  </div>
                  <p className="mt-2 text-xs text-white/40">
                    Pledge <span className="font-mono">{shortId(dispute.pledgeId)}</span> · fee{' '}
                    {formatMoney(dispute.fee)} · <Tag>{dispute.state}</Tag>
                  </p>
                </button>

                {openCase === dispute.id && <CaseDetail disputeId={dispute.id} onChanged={queue.reload} />}
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

/**
 * How long is left.
 *
 * Three renderings, because they mean different things: a case with no deadline is not urgent,
 * one that has expired cannot be answered, and one with hours left is the reason somebody
 * opened this screen.
 */
function Deadline({ dispute }: { readonly dispute: Dispute }) {
  const hours = hoursUntilDue(dispute);

  if (hours === null) {
    return <span className="text-xs text-white/40">No deadline given</span>;
  }
  if (hours < 0) {
    return <Tag>Deadline passed</Tag>;
  }
  return (
    <span className="text-xs text-white/64">
      {hours < 48 ? `${Math.floor(hours)}h left` : `${Math.floor(hours / 24)}d left`}
    </span>
  );
}

/** One case, its evidence, and the two things staff can do to it. */
function CaseDetail({
  disputeId,
  onChanged,
}: {
  readonly disputeId: string;
  readonly onChanged: () => void;
}) {
  const detail = useConsoleResource((signal) => readDispute(disputeId, signal), 'this case', [disputeId]);

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
      setError(consoleMessageFor(cause, 'this case'));
    } finally {
      setBusy(false);
    }
  }

  if (detail.status === 'loading') {
    return (
      <SkeletonGroup label="Loading the case" className="mt-2 rounded-lg border border-white/8 p-4">
        <Skeleton height="0.875rem" width="60%" />
      </SkeletonGroup>
    );
  }

  if (detail.status !== 'ready' || detail.data === null) {
    return (
      <InlineAlert variant="danger" title="The case could not be read" className="mt-2">
        {detail.error ?? 'Try again.'}
      </InlineAlert>
    );
  }

  const dispute = detail.data;

  return (
    <div className="mt-2 rounded-lg border border-white/8 bg-surface-1 p-4">
      <h3 className="text-sm font-medium text-white">Evidence</h3>

      {dispute.evidence.length === 0 ? (
        <p className="mt-2 text-xs text-white/48">
          Nothing has been assembled. A case answered with no evidence is a case conceded slowly.
        </p>
      ) : (
        <ul className="mt-2 flex list-none flex-col gap-2">
          {dispute.evidence.map((piece) => (
            <li key={piece.id} className="rounded-md border border-white/8 p-3">
              <p className="text-xs text-white/80">{EVIDENCE_KIND_LABELS[piece.kind]}</p>
              <p className="mt-1 text-xs text-white/64">{piece.description}</p>
              <p className="mt-1 text-xs text-white/40">
                {piece.submittedAt == null ? 'Not sent yet' : 'Sent'}
              </p>
            </li>
          ))}
        </ul>
      )}

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <Field label="Kind" className="min-w-[220px]">
          <Select value={kind} onChange={(event) => setKind(event.target.value as EvidenceKind)}>
            {KINDS.map((option) => (
              <option key={option} value={option}>
                {EVIDENCE_KIND_LABELS[option]}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="What it shows" className="min-w-[280px] flex-1">
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
          Add
        </Pill>
      </div>

      <div className="mt-4 flex flex-wrap gap-2 border-t border-white/8 pt-4">
        <Pill
          variant="outline"
          size="sm"
          disabled={busy}
          onClick={() => void act(() => submitEvidence(dispute.id))}
        >
          Mark answered
        </Pill>

        {DISPUTE_OUTCOMES.map((outcome) => (
          <Pill
            key={outcome}
            variant="ghost"
            size="sm"
            disabled={busy}
            onClick={() => void act(() => resolveDispute(dispute.id, outcome as DisputeState))}
          >
            {outcome}
          </Pill>
        ))}
      </div>

      <p className="mt-3 text-xs text-white/40">
        Recording LOST or CONCEDED posts the disputed amount and the provider&apos;s fee to the
        ledger. WON posts nothing, because nothing moved.
      </p>

      {error && (
        <InlineAlert variant="danger" title="That did not work" className="mt-4">
          {error}
        </InlineAlert>
      )}
    </div>
  );
}
