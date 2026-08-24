'use client';

import { useState } from 'react';
import {
  EmptyState,
  Field,
  InlineAlert,
  Pill,
  Skeleton,
  SkeletonGroup,
  Switch,
  Tag,
  TextInput,
} from '@ideanest/ui';
import { FLAG_KEY_PATTERN, readFlags, saveFlag, type FeatureFlag } from '../../lib/admin/flags';
import { consoleMessageFor } from '../../lib/admin/refusals';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the feature flags';

/**
 * §4.11's AD-12: gradual rollout and experiments — issue #312.
 *
 * <h2>The switch is a kill switch and the screen says so</h2>
 *
 * Turning a flag off turns it off for everybody, including the accounts named explicitly on
 * it. That is the property somebody is relying on when they reach for this during an incident,
 * and an interface that left it ambiguous would be one nobody trusts at the moment it matters.
 *
 * <h2>A percentage only ever grows</h2>
 *
 * Which accounts fall inside a rollout is decided by a stable hash of the flag and the account,
 * so widening one adds people and never takes the feature away from somebody who had it
 * yesterday. Worth stating on the screen, because the intuition from a sampled cohort is the
 * opposite.
 *
 * <h2>There is no delete</h2>
 *
 * Code asking for a flag that no longer exists gets the fail-closed default silently, so a
 * deleted row and a row that never existed are indistinguishable from the application&apos;s
 * side. A flag is switched off instead, which leaves the row saying who switched it off.
 */
export function FlagConsole() {
  const flags = useConsoleResource((signal) => readFlags(signal), SUBJECT, []);

  const [key, setKey] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (flags.status === 'signed-out' || flags.status === 'forbidden') {
    return <ConsoleRefusal status={flags.status} subject={SUBJECT} />;
  }

  async function save(flag: FeatureFlag, change: Partial<FeatureFlag>): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await saveFlag({
        key: flag.key,
        description: change.description ?? flag.description,
        enabled: change.enabled ?? flag.enabled,
        rolloutPercentage: change.rolloutPercentage ?? flag.rolloutPercentage,
        enabledAccounts: change.enabledAccounts ?? flag.enabledAccounts,
      });
      flags.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setBusy(false);
    }
  }

  async function create(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    const name = key.trim();
    if (!FLAG_KEY_PATTERN.test(name) || description.trim() === '') return;

    setBusy(true);
    setError(null);
    try {
      // Created off and at nought percent, always. A flag that arrived switched on would be a
      // feature shipped by the act of naming it.
      await saveFlag({
        key: name,
        description: description.trim(),
        enabled: false,
        rolloutPercentage: 0,
        enabledAccounts: [],
      });
      setKey('');
      setDescription('');
      flags.reload();
    } catch (cause) {
      setError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setBusy(false);
    }
  }

  const keyIsValid = key.trim() === '' || FLAG_KEY_PATTERN.test(key.trim());

  return (
    <div className="flex flex-col gap-10">
      <section aria-labelledby="flags-heading">
        <h2 id="flags-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Flags
          {flags.status === 'ready' && (
            <span className="ml-2 text-xs font-normal text-white/40">
              {flags.data?.flags.length ?? 0}
            </span>
          )}
        </h2>

        {flags.status === 'loading' && (
          <SkeletonGroup label="Loading feature flags" className="mt-4">
            <div className="space-y-3">
              {[0, 1].map((row) => (
                <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                  <Skeleton height="1rem" width="35%" />
                  <Skeleton height="0.875rem" width="65%" className="mt-3" />
                </div>
              ))}
            </div>
          </SkeletonGroup>
        )}

        {flags.status === 'failed' && (
          <>
            <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
              {flags.error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={flags.reload}>
              Try again
            </Pill>
          </>
        )}

        {flags.status === 'ready' && flags.data !== null && flags.data.flags.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title="No flags"
            description="Nothing on this deployment is behind a switch. Code asking for a flag that does not exist gets false, so the platform behaves as though every unbuilt feature is off — which is the safe direction."
          />
        )}

        {flags.status === 'ready' && flags.data !== null && flags.data.flags.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-2">
            {flags.data.flags.map((flag) => (
              <li key={flag.key} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-mono text-sm text-white">{flag.key}</p>
                    <p className="mt-1 text-xs text-white/64">{flag.description}</p>
                  </div>

                  <Switch
                    checked={flag.enabled}
                    disabled={busy}
                    label={flag.enabled ? 'On' : 'Off'}
                    onCheckedChange={(next) => void save(flag, { enabled: next })}
                  />
                </div>

                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <span className="text-xs text-white/48">Rollout</span>
                  {[0, 10, 25, 50, 100].map((percentage) => (
                    <Pill
                      key={percentage}
                      variant={flag.rolloutPercentage === percentage ? 'outline' : 'ghost'}
                      size="sm"
                      disabled={busy}
                      onClick={() => void save(flag, { rolloutPercentage: percentage })}
                    >
                      {percentage}%
                    </Pill>
                  ))}
                  {flag.enabledAccounts.length > 0 && (
                    <Tag>{flag.enabledAccounts.length} always in</Tag>
                  )}
                </div>

                <p className="mt-3 text-xs text-white/40">
                  Last changed {flag.updatedAt.slice(0, 10)}
                  {flag.enabled ? '' : ' · off for everybody, including the accounts named on it'}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="new-flag-heading">
        <h2 id="new-flag-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Add a flag
        </h2>
        <p className="mt-2 max-w-[62ch] text-sm text-white/64">
          New flags arrive off and at nought percent. Widening a rollout only ever adds people —
          the accounts inside it are decided by a stable hash rather than a sample, so nobody
          loses a feature because the percentage went up.
        </p>

        <form onSubmit={(event) => void create(event)} className="mt-4 flex flex-wrap items-end gap-3">
          <Field
            label="Name"
            hint="Lower case and hyphenated, as the code asks for it."
            error={keyIsValid ? undefined : 'Lower case letters, digits and hyphens.'}
            className="min-w-[240px]"
          >
            <TextInput
              value={key}
              onChange={(event) => setKey(event.target.value)}
              placeholder="checkout-v2"
            />
          </Field>

          <Field label="What it switches" className="min-w-[280px] flex-1">
            <TextInput
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={2000}
            />
          </Field>

          <Pill type="submit" variant="outline" size="sm" className="mb-1" disabled={busy || !keyIsValid}>
            {busy ? 'Working' : 'Add'}
          </Pill>
        </form>

        {error && (
          <InlineAlert variant="danger" title="That did not work" className="mt-4">
            {error}
          </InlineAlert>
        )}
      </section>
    </div>
  );
}
