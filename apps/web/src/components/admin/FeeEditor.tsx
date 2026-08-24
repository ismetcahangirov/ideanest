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
  TextInput,
} from '@ideanest/ui';
import {
  asPercentage,
  readFeeHistory,
  replaceFeeSchedule,
  type FeeSchedule,
  type FeeScope,
} from '../../lib/admin/fees';
import { consoleMessageFor, shortId } from '../../lib/admin/refusals';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the fee schedule';

const SCOPES: readonly FeeScope[] = ['PLATFORM', 'CATEGORY', 'PROJECT'];

/**
 * §4.11's AD-11: platform and processing rates, with exceptions — §9, issue #311.
 *
 * <h2>The screen says out loud that there is no edit</h2>
 *
 * An operator opening this expects to change a number. What actually happens is that the
 * schedule in force is closed and a new one opens beginning now, because a rate is a term
 * rather than a setting: a pledge collected in March was collected under March's terms, and
 * editing in place would silently rewrite what every past payout should have been.
 *
 * Saying that in the interface is not decoration. Without it, somebody submits what they think
 * is an edit, sees a second row appear, and concludes the screen is broken.
 *
 * <h2>Rates are typed as fractions, and the screen shows the percentage beside them</h2>
 *
 * `0.05` is five percent. The wire carries the number that gets multiplied, as a string,
 * because a JSON number is an IEEE 754 double in every mainstream parser and a percentage
 * would be divided by a hundred by whichever call site remembered. What the reader sees is the
 * percentage, computed for display only — nothing on this screen multiplies a rate by money.
 *
 * <h2>Closed schedules stay on the screen</h2>
 *
 * They are the answer to "what did we charge in March", which is a question §22.1 attaches a
 * seven-year retention rule to. A list of only the open ones would be three rows that could
 * have been configuration.
 */
export function FeeEditor() {
  const history = useConsoleResource((signal) => readFeeHistory(signal), SUBJECT, []);

  const [scope, setScope] = useState<FeeScope>('PLATFORM');
  const [scopeRef, setScopeRef] = useState('');
  const [platformRate, setPlatformRate] = useState('0.05');
  const [processingRate, setProcessingRate] = useState('0.029');
  const [processingFixed, setProcessingFixed] = useState('0.30');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [written, setWritten] = useState<string | null>(null);

  if (history.status === 'signed-out' || history.status === 'forbidden') {
    return <ConsoleRefusal status={history.status} subject={SUBJECT} />;
  }

  async function replace(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    if (note.trim() === '') return;

    setBusy(true);
    setError(null);
    setWritten(null);
    try {
      const schedule = await replaceFeeSchedule({
        scope,
        scopeRef: scope === 'PLATFORM' ? null : scopeRef.trim(),
        platformRate: platformRate.trim(),
        processingRate: processingRate.trim(),
        processingFixed: processingFixed.trim(),
        currency: 'AZN',
        note: note.trim(),
      });

      setWritten(
        `New ${schedule.scope} terms in force from ${schedule.effectiveFrom.slice(0, 10)}. The previous schedule is closed, not deleted.`,
      );
      setNote('');
      history.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setBusy(false);
    }
  }

  const open = history.data?.schedules.filter((schedule) => schedule.open) ?? [];
  const closed = history.data?.schedules.filter((schedule) => !schedule.open) ?? [];

  return (
    <div className="flex flex-col gap-10">
      <InlineAlert variant="info" title="Changing a fee opens a new schedule">
        There is no edit. Submitting below closes the terms currently in force and opens new ones
        beginning now, so a payout calculated last month still prices against last month&apos;s
        rates. Nothing you can do here changes what was charged in the past.
      </InlineAlert>

      <section aria-labelledby="in-force-heading">
        <h2 id="in-force-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          In force
        </h2>

        {history.status === 'loading' && (
          <SkeletonGroup label="Loading fee schedules" className="mt-4">
            <Skeleton height="1rem" width="40%" />
            <Skeleton height="0.875rem" width="60%" className="mt-3" />
          </SkeletonGroup>
        )}

        {history.status === 'failed' && (
          <>
            <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
              {history.error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={history.reload}>
              Try again
            </Pill>
          </>
        )}

        {history.status === 'ready' && open.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title="No fee schedule is configured"
            description="The platform is currently pricing every payout at zero fees. That is deliberate — a payout run must not stop because nobody wrote a row — but it means creators are being paid the full amount collected."
          />
        )}

        {history.status === 'ready' && open.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-2">
            {open.map((schedule) => (
              <ScheduleRow key={schedule.id} schedule={schedule} />
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="replace-heading">
        <h2 id="replace-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Open new terms
        </h2>

        <form onSubmit={(event) => void replace(event)} className="mt-4 flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <Field label="Scope" className="min-w-[180px]">
              <Select value={scope} onChange={(event) => setScope(event.target.value as FeeScope)}>
                {SCOPES.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </Select>
            </Field>

            {scope !== 'PLATFORM' && (
              <Field
                label={scope === 'CATEGORY' ? 'Category' : 'Campaign'}
                hint="The whole identifier."
                className="min-w-[280px] flex-1"
              >
                <TextInput value={scopeRef} onChange={(event) => setScopeRef(event.target.value)} />
              </Field>
            )}
          </div>

          <div className="flex flex-wrap items-end gap-3">
            <Field
              label="Platform rate"
              hint={`A fraction. ${asPercentage(platformRate)}`}
              className="min-w-[180px]"
            >
              <TextInput
                inputMode="decimal"
                value={platformRate}
                onChange={(event) => setPlatformRate(event.target.value)}
              />
            </Field>

            <Field
              label="Processing rate"
              hint={`A fraction. ${asPercentage(processingRate)}`}
              className="min-w-[180px]"
            >
              <TextInput
                inputMode="decimal"
                value={processingRate}
                onChange={(event) => setProcessingRate(event.target.value)}
              />
            </Field>

            <Field label="Fixed per transaction" className="min-w-[180px]">
              <TextInput
                inputMode="decimal"
                value={processingFixed}
                onChange={(event) => setProcessingFixed(event.target.value)}
              />
            </Field>
          </div>

          <Field label="Why" hint="Required. A fee change nobody explained is one nobody can defend.">
            <TextInput value={note} onChange={(event) => setNote(event.target.value)} maxLength={2000} />
          </Field>

          <div>
            <Pill type="submit" variant="outline" size="sm" disabled={busy}>
              {busy ? 'Working' : 'Open new terms'}
            </Pill>
          </div>
        </form>

        {written && (
          <InlineAlert variant="success" title="Done" className="mt-4">
            {written}
          </InlineAlert>
        )}
        {error && (
          <InlineAlert variant="danger" title="That did not work" className="mt-4">
            {error}
          </InlineAlert>
        )}
      </section>

      {history.status === 'ready' && closed.length > 0 && (
        <section aria-labelledby="past-terms-heading">
          <h2 id="past-terms-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
            Past terms
          </h2>
          <p className="mt-2 max-w-[62ch] text-sm text-white/64">
            What the platform charged, and when. §22.1 makes this a record with a retention rule
            rather than a changelog.
          </p>

          <ul className="mt-4 flex list-none flex-col gap-2">
            {closed.map((schedule) => (
              <ScheduleRow key={schedule.id} schedule={schedule} />
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

/** One set of terms and the window it applied over. */
function ScheduleRow({ schedule }: { readonly schedule: FeeSchedule }) {
  return (
    <li className="rounded-lg border border-white/8 bg-surface-1 p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-sm text-white">
          {schedule.scope}
          {schedule.scopeRef ? (
            <span className="ml-2 font-mono text-white/48">{shortId(schedule.scopeRef)}</span>
          ) : null}
        </p>
        {schedule.open ? <Tag>In force</Tag> : null}
      </div>

      <p className="mt-2 text-sm text-white/80">
        Platform {asPercentage(schedule.platformRate)} · processing{' '}
        {asPercentage(schedule.processingRate)} + {schedule.processingFixed} {schedule.currency}
      </p>

      <p className="mt-2 text-xs text-white/40">
        {schedule.effectiveFrom.slice(0, 10)} —{' '}
        {schedule.effectiveTo == null ? 'now' : schedule.effectiveTo.slice(0, 10)} · {schedule.note}
      </p>
    </li>
  );
}
