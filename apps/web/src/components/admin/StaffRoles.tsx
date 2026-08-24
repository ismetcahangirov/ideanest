'use client';

import { useState } from 'react';
import { EmptyState, Field, InlineAlert, Pill, Select, Skeleton, SkeletonGroup, Tag, TextInput } from '@ideanest/ui';
import {
  CAPABILITY_LABELS,
  ROLE_CAPABILITIES,
  grantRole,
  readMembership,
  readRoster,
  revokeRole,
  type StaffCapability,
  type StaffGrant,
  type StaffRole,
} from '../../lib/admin/staff';
import { consoleMessageFor, shortId } from '../../lib/admin/refusals';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the staff roster';

const ROLES: readonly StaffRole[] = ['MODERATOR', 'CURATOR', 'FINANCE', 'ADMINISTRATOR'];

/**
 * §4.11's role model, as a screen — issue #295.
 *
 * <h2>What was here before</h2>
 *
 * Nothing, and there could not have been. Staff identity was one comma-separated
 * environment variable, so "who works here" had no answer the platform could render and no
 * answer anybody could change without a deployment. #295's own words for the problem: that
 * list "cannot express 'may refund' against 'may moderate'".
 *
 * <h2>Two panels, and the first one is about the reader</h2>
 *
 * The console now knows what the person reading it may do, so the first thing this screen
 * shows is that — every capability they hold and the roles it came from. That is not
 * decoration: the commonest question a member of staff has about a console is why a screen
 * they were told about is not on their rail, and this answers it without anybody having to
 * ask an administrator.
 *
 * <h2>Bootstrapped administrators are called out</h2>
 *
 * An account that is staff through `IDEANEST_STAFF_BOOTSTRAP_EMAILS` rather than through a
 * grant cannot be withdrawn from this screen — it is in a deployment's environment, and the
 * only way to remove it is to change that and restart. A roster that showed them as ordinary
 * administrators would be a list somebody would try to revoke from and quietly fail.
 *
 * <h2>Motion: none</h2>
 *
 * docs/motion-system.md §5 gives an administrative surface 150ms of colour on a control and
 * nothing that moves. This one changes who can move money; it is the last screen on the
 * platform that should feel playful.
 */
export function StaffRoles() {
  const me = useConsoleResource((signal) => readMembership(signal), 'your own standing', []);
  const roster = useConsoleResource((signal) => readRoster(signal), SUBJECT, []);

  const [accountId, setAccountId] = useState('');
  const [role, setRole] = useState<StaffRole>('MODERATOR');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [writeError, setWriteError] = useState<string | null>(null);
  const [written, setWritten] = useState<string | null>(null);

  // `me` is read first and refuses nobody, so a signed-out reader is caught here rather
  // than by the roster — which would otherwise report "not staff" to somebody whose session
  // had merely expired.
  if (me.status === 'signed-out') {
    return <ConsoleRefusal status="signed-out" subject={SUBJECT} />;
  }

  async function submit(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    const id = accountId.trim();
    if (id === '') return;

    setBusy(true);
    setWriteError(null);
    setWritten(null);
    try {
      await grantRole(id, role, note.trim() === '' ? null : note.trim());
      setWritten(`${role} granted to ${shortId(id)}.`);
      setAccountId('');
      setNote('');
      roster.reload();
    } catch (cause) {
      setWriteError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setBusy(false);
    }
  }

  async function withdraw(grant: StaffGrant): Promise<void> {
    setBusy(true);
    setWriteError(null);
    setWritten(null);
    try {
      await revokeRole(grant.accountId, grant.role);
      setWritten(`${grant.role} withdrawn from ${shortId(grant.accountId)}.`);
      roster.reload();
    } catch (cause) {
      setWriteError(consoleMessageFor(cause, SUBJECT));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-10">
      <section aria-labelledby="my-standing-heading">
        <h2 id="my-standing-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          What you may do
        </h2>

        {me.status === 'loading' && (
          <SkeletonGroup label="Reading your standing" className="mt-4">
            <Skeleton height="1rem" width="30%" />
            <Skeleton height="0.875rem" width="70%" className="mt-3" />
          </SkeletonGroup>
        )}

        {me.status === 'ready' && me.data !== null && !me.data.staff && (
          <InlineAlert variant="info" title="You do not work here" className="mt-4">
            Your account holds no platform role, so the console has nothing to show you. This
            is not an error — the service refuses every screen behind this one, and the page
            you are on is simply saying so honestly.
          </InlineAlert>
        )}

        {me.status === 'ready' && me.data !== null && me.data.staff && (
          <div className="mt-4 rounded-lg border border-white/8 bg-surface-1 p-4">
            <p className="text-sm text-white/64">
              Signed in as <span className="font-mono text-white/80">{shortId(me.data.accountId)}</span>, holding{' '}
              {me.data.roles.map((held) => (
                <Tag key={held} className="mx-1">
                  {held}
                </Tag>
              ))}
            </p>

            {me.data.bootstrapped && (
              <InlineAlert variant="warning" title="Administrator by configuration" className="mt-4">
                Your account is an administrator because its address is in{' '}
                <code className="font-mono text-xs">MODERATOR_EMAILS</code>, not because anybody
                granted it a role. That is the bootstrap the platform needs to make its first
                grant — once real roles are in place, empty that variable. Nobody can withdraw
                this from the console.
              </InlineAlert>
            )}

            <ul className="mt-4 flex list-none flex-wrap gap-2">
              {me.data.capabilities.map((capability) => (
                <li
                  key={capability}
                  className="rounded-md border border-white/8 px-2.5 py-1.5 text-xs text-white/64"
                  title={CAPABILITY_LABELS[capability as StaffCapability]}
                >
                  {capability}
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>

      <section aria-labelledby="roster-heading">
        <h2 id="roster-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Who holds what
        </h2>

        {roster.status === 'forbidden' && (
          <InlineAlert variant="info" title="Not yours to read" className="mt-4">
            The roster names everybody who can move money on this platform, so reading it needs{' '}
            <code className="font-mono text-xs">ADMINISTER_STAFF</code> — which only an
            administrator holds. Your own standing is above, and it is the part that concerns
            you.
          </InlineAlert>
        )}

        {roster.status === 'loading' && (
          <SkeletonGroup label="Loading the roster" className="mt-4">
            <div className="space-y-3">
              {[0, 1].map((row) => (
                <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
                  <Skeleton height="1rem" width="45%" />
                </div>
              ))}
            </div>
          </SkeletonGroup>
        )}

        {roster.status === 'failed' && (
          <>
            <InlineAlert variant="danger" title="Something went wrong" className="mt-4">
              {roster.error}
            </InlineAlert>
            <Pill variant="ghost" size="sm" className="mt-4" onClick={roster.reload}>
              Try again
            </Pill>
          </>
        )}

        {roster.status === 'ready' && roster.data !== null && roster.data.grants.length === 0 && (
          <EmptyState
            className="mt-4"
            variant="empty"
            title="Nobody holds a granted role"
            description="Every member of staff on this deployment is an administrator through the bootstrap list. Grant real roles below and then empty that variable."
          />
        )}

        {roster.status === 'ready' && roster.data !== null && roster.data.grants.length > 0 && (
          <ul className="mt-4 flex list-none flex-col gap-2">
            {roster.data.grants.map((grant) => (
              <li
                key={`${grant.accountId}:${grant.role}`}
                className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-white/8 bg-surface-1 p-4"
              >
                <div className="min-w-0">
                  <p className="text-sm text-white">
                    <span className="font-mono text-white/80">{shortId(grant.accountId)}</span>{' '}
                    <Tag className="ml-1">{grant.role}</Tag>
                  </p>
                  <p className="mt-1 text-xs text-white/48">
                    Granted by <span className="font-mono">{shortId(grant.grantedBy)}</span> on{' '}
                    {new Date(grant.grantedAt).toISOString().slice(0, 10)}
                    {grant.note ? ` — ${grant.note}` : ''}
                  </p>
                </div>

                <Pill variant="ghost" size="sm" disabled={busy} onClick={() => void withdraw(grant)}>
                  Withdraw
                </Pill>
              </li>
            ))}
          </ul>
        )}
      </section>

      {roster.status !== 'forbidden' && (
        <section aria-labelledby="grant-heading">
          <h2 id="grant-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
            Grant a role
          </h2>
          <p className="mt-2 max-w-[62ch] text-sm text-white/64">
            Roles are additive: an account holds the union of every role granted to it. Nothing
            here takes a capability away, which is why there is no order to resolve.
          </p>

          <form onSubmit={(event) => void submit(event)} className="mt-4 flex flex-wrap items-end gap-3">
            <Field
              label="Account"
              hint="The whole identifier, from the account directory."
              className="min-w-[280px] flex-1"
            >
              <TextInput
                value={accountId}
                onChange={(event) => setAccountId(event.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000"
              />
            </Field>

            <Field label="Role" className="min-w-[180px]">
              <Select value={role} onChange={(event) => setRole(event.target.value as StaffRole)}>
                {ROLES.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </Select>
            </Field>

            <Field label="Note" hint="Why, for the next administrator." className="min-w-[240px] flex-1">
              <TextInput value={note} onChange={(event) => setNote(event.target.value)} />
            </Field>

            <Pill type="submit" variant="outline" size="sm" className="mb-1" disabled={busy}>
              {busy ? 'Working' : 'Grant'}
            </Pill>
          </form>

          <p className="mt-3 text-xs text-white/48">
            {role} confers: {ROLE_CAPABILITIES[role].join(', ')}
          </p>

          {written && (
            <InlineAlert variant="success" title="Done" className="mt-4">
              {written}
            </InlineAlert>
          )}
          {writeError && (
            <InlineAlert variant="danger" title="That did not work" className="mt-4">
              {writeError}
            </InlineAlert>
          )}
        </section>
      )}
    </div>
  );
}
