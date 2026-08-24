'use client';

import { InlineAlert, Pill, Skeleton, SkeletonGroup, Tag } from '@ideanest/ui';
import { ageInSeconds, readHealth, type HealthStatus } from '../../lib/admin/health';
import { ConsoleRefusal } from './ConsoleRefusal';
import { useConsoleResource } from './useConsoleResource';

const SUBJECT = 'the health dashboard';

/**
 * §4.11's AD-16: queue depth, failed jobs, provider status — §18, issue #316.
 *
 * <h2>It says it is not monitoring, and that is load-bearing</h2>
 *
 * #316 was labelled blocked on #138, and it was not quite: every number here is a count the
 * service can already take, so the page needs no collector and no exporter. What it does not do
 * is alert — nobody is woken by anything on this screen — and a dashboard presented as though
 * it were monitoring is worse than an honest gap, because the gap then looks filled.
 *
 * The banner comes from the service&apos;s `monitored` field rather than being hard-coded, so
 * the day #138 lands it disappears without a change here.
 *
 * <h2>Waiting and dead are never added together</h2>
 *
 * A deep queue is a platform under load and will drain. A dead row is a platform that has given
 * up and will not. One number would let a thousand-item backlog hide the one message that will
 * never be sent, so any dead row is critical regardless of depth.
 *
 * <h2>Not configured is not unhealthy</h2>
 *
 * §9.3 asks for at least two payment integrations and a deployment may run one. Painting the
 * others red would put permanent failures on a screen whose whole job is to show the ones that
 * are not permanent.
 */
export function HealthDashboard() {
  const health = useConsoleResource((signal) => readHealth(signal), SUBJECT, []);

  if (health.status === 'signed-out' || health.status === 'forbidden') {
    return <ConsoleRefusal status={health.status} subject={SUBJECT} />;
  }

  if (health.status === 'loading') {
    return (
      <SkeletonGroup label="Loading the health dashboard">
        <div className="space-y-3">
          {[0, 1, 2].map((row) => (
            <div key={row} className="rounded-lg border border-white/8 bg-surface-1 p-4">
              <Skeleton height="1rem" width="35%" />
              <Skeleton height="0.875rem" width="60%" className="mt-3" />
            </div>
          ))}
        </div>
      </SkeletonGroup>
    );
  }

  if (health.status === 'failed' || health.data === null) {
    return (
      <>
        <InlineAlert variant="danger" title="Something went wrong">
          {health.error ?? 'The dashboard could not be read.'}
        </InlineAlert>
        <Pill variant="ghost" size="sm" className="mt-4" onClick={health.reload}>
          Try again
        </Pill>
      </>
    );
  }

  const snapshot = health.data;
  const age = ageInSeconds(snapshot);

  return (
    <div className="flex flex-col gap-8">
      {!snapshot.monitored && (
        <InlineAlert variant="info" title="This page does not alert anybody">
          Everything here is measured when you open it. Nothing on this screen wakes anybody, and
          nothing watches it while you are not looking — §18&apos;s observability work (#138) is
          what will. Read it when you suspect something; do not rely on it to tell you.
        </InlineAlert>
      )}

      <section aria-labelledby="overall-heading">
        <h2 id="overall-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Overall <StatusTag status={snapshot.status} />
        </h2>
        <p className="mt-2 text-xs text-white/48">
          Measured {age < 60 ? `${age} seconds` : `${Math.floor(age / 60)} minutes`} ago.{' '}
          <button
            type="button"
            onClick={health.reload}
            className="rounded text-white/64 underline transition-colors duration-150 ease-in-out hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--lime-500)]"
          >
            Measure again
          </button>
        </p>
      </section>

      <section aria-labelledby="queues-heading">
        <h2 id="queues-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Queues
        </h2>
        <ul className="mt-4 flex list-none flex-col gap-2">
          {snapshot.queues.map((queue) => (
            <li
              key={queue.name}
              className="flex flex-wrap items-baseline justify-between gap-2 rounded-lg border border-white/8 bg-surface-1 p-4"
            >
              <p className="text-sm text-white">{queue.name}</p>
              <p className="text-sm text-white/64">
                {queue.waiting} waiting
                {queue.dead > 0 ? ` · ${queue.dead} given up` : ''}{' '}
                <StatusTag status={queue.status} />
              </p>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="jobs-heading">
        <h2 id="jobs-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Scheduled jobs
        </h2>
        <ul className="mt-4 flex list-none flex-col gap-2">
          {snapshot.jobs.map((job) => (
            <li key={job.name} className="rounded-lg border border-white/8 bg-surface-1 p-4">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <p className="font-mono text-sm text-white">{job.name}</p>
                <StatusTag status={job.status} />
              </div>
              <p className="mt-2 text-xs text-white/48">
                {job.state}
                {job.overdueBySeconds > 0
                  ? ` · overdue by ${Math.floor(job.overdueBySeconds / 60)} minutes`
                  : ' · on time'}
                {job.attempts > 0 ? ` · ${job.attempts} attempts` : ''}
              </p>
              {job.lastError && (
                <p className="mt-2 break-words text-xs text-white/64">{job.lastError}</p>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="providers-heading">
        <h2 id="providers-heading" className="text-lg font-medium tracking-[-0.02em] text-white">
          Providers
        </h2>
        <ul className="mt-4 flex list-none flex-col gap-2">
          {snapshot.providers.map((provider) => (
            <li
              key={`${provider.kind}:${provider.provider}`}
              className="rounded-lg border border-white/8 bg-surface-1 p-4"
            >
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <p className="text-sm text-white">
                  {provider.provider}
                  <span className="ml-2 text-white/40">{provider.kind}</span>
                </p>
                {provider.configured ? (
                  <StatusTag status={provider.status} />
                ) : (
                  <Tag>Not configured</Tag>
                )}
              </div>
              {provider.detail && <p className="mt-2 text-xs text-white/48">{provider.detail}</p>}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

/**
 * The status, as a word rather than only a colour.
 *
 * CLAUDE.md: colour alone must never carry meaning. A dashboard is exactly where that rule
 * is most often broken and most expensive to break, because the whole page is a colour.
 */
function StatusTag({ status }: { readonly status: HealthStatus }) {
  const label = status === 'HEALTHY' ? 'Healthy' : status === 'DEGRADED' ? 'Worth a look' : 'Stopped';
  return <Tag>{label}</Tag>;
}
